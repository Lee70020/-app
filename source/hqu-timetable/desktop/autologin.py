# -*- coding: utf-8 -*-
"""HQU unified-auth auto login with slider captcha solving.

Reusable module: login(session, username, password) -> bool
Credentials come from caller; never logged or written to disk.
"""
import base64
import json
import random
import re
import string
import time

import cv2
import numpy as np
import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

AUTH = 'https://id.hqu.edu.cn/authserver'
SERVICE = 'https://jwapp.hqu.edu.cn/jwapp/sys/emaphome/portal/index.do?forceCas=1'
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36')
CHARS = 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678'


def _rand(n):
    return ''.join(random.choice(CHARS) for _ in range(n))


def encrypt(text, salt):
    """encryptPassword(): AES-128-CBC(key=salt, iv=random16) over rand64+text."""
    data = (_rand(64) + text).encode('utf-8')
    cipher = AES.new(salt.encode('utf-8'), AES.MODE_CBC, _rand(16).encode('utf-8'))
    return base64.b64encode(cipher.encrypt(pad(data, 16))).decode()


def find_gap(big_bytes, small_bytes):
    """Locate hole x in big-image coordinates.

    The hole is a translucent piece-shaped overlay over the original content:
    zero-mean color NCC between the piece (masked by alpha) and each window
    peaks at the hole position (~0.6-0.96); raw sqdiff fails due to dimming.
    Returns (hole_x, big_width).
    """
    big = cv2.imdecode(np.frombuffer(big_bytes, np.uint8), cv2.IMREAD_COLOR)
    small = cv2.imdecode(np.frombuffer(small_bytes, np.uint8), cv2.IMREAD_UNCHANGED)
    if big is None or small is None or small.shape[2] < 4:
        raise RuntimeError('captcha image decode failed')
    alpha = small[:, :, 3]
    ys, xs = np.where(alpha > 0)
    y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
    ph, pw = y1 - y0 + 1, x1 - x0 + 1

    bigf = big.astype(np.float32)
    piece = small[y0:y1 + 1, x0:x1 + 1, :3].astype(np.float32)
    a = (alpha[y0:y1 + 1, x0:x1 + 1] > 0).astype(np.float32)
    a3 = np.repeat(a[:, :, None], 3, axis=2)
    n3 = a3.sum()
    pm = (piece * a3).sum() / n3
    pv = np.sqrt((((piece - pm) * a3) ** 2).sum() / n3)
    pt = (piece - pm) * a3

    W = big.shape[1]
    best_x, best_v = 0, -2.0
    for xx in range(0, W - pw):
        win = bigf[y0:y1 + 1, xx:xx + pw]
        wm = (win * a3).sum() / n3
        wv = np.sqrt((((win - wm) * a3) ** 2).sum() / n3)
        if wv < 1e-6:
            continue
        v = float(((win - wm) * pt).sum() / (wv * pv * n3))
        if v > best_v:
            best_v, best_x = v, xx
    return best_x, W


def human_tracks(distance):
    """Simulate the widget's drag track: [{a:dx,b:dy,c:dt},...]."""
    tracks = [{'a': 0, 'b': 0, 'c': 0}]
    x, t, y = 0, 0, 0
    n = random.randint(18, 30)
    for i in range(1, n + 1):
        # ease-in-out progression with jitter
        p = i / n
        ease = p * p * (3 - 2 * p)
        x = int(distance * ease + random.uniform(-1, 1))
        y = int(random.uniform(-1.5, 1.5))
        t = random.randint(20, 60)
        tracks.append({'a': max(0, x), 'b': y, 'c': t})
    tracks.append({'a': int(distance), 'b': 0, 'c': random.randint(20, 50)})
    return tracks


def solve_slider(session, debug=False):
    """Fetch + solve slider captcha; returns True when server accepts.

    Failed verify attempts do NOT consume the captcha (only success does),
    so we probe a small window around the estimated move, then fall back to
    a coarse scan. Requests are throttled to avoid server rate limiting.
    """
    r = session.get(AUTH + '/common/openSliderCaptcha.htl', timeout=20,
                    headers={'Referer': AUTH + '/login'})
    data = r.json()
    if not data.get('bigImage'):
        return False  # not required right now
    small_raw = base64.b64decode(data['smallImage'])
    big_raw = base64.b64decode(data['bigImage'])
    key = small_raw[-16:].decode('ascii', errors='ignore')
    gap_x, big_w = find_gap(big_raw, small_raw)
    canvas = 280
    est = round(gap_x / big_w * canvas)
    if debug:
        print('  captcha: gap_x=%d big_w=%d -> move~%d' % (gap_x, big_w, est))

    def verify(move):
        payload = json.dumps({'canvasLength': canvas, 'moveLength': move,
                              'tracks': human_tracks(move)},
                             separators=(',', ':'))
        rr = session.post(AUTH + '/common/verifySliderCaptcha.htl',
                          data={'sign': encrypt(payload, key)}, timeout=20,
                          headers={'Referer': AUTH + '/login'})
        time.sleep(0.25)
        body = rr.json()
        if debug:
            print('  verify move=%d -> %s' % (move, body))
        return body.get('errorCode') == 1

    # primary: small window around NCC estimate
    for off in (0, -3, 3, -6, 6, -9, 9, -12, 12):
        move = max(0, min(238, est + off))
        if verify(move):
            return True
    # fallback: coarse scan across the full range
    for move in range(0, 239, 10):
        if verify(move):
            return True
    return False


def login(session, username, password):
    """Full flow: page -> (captcha) -> POST -> jwapp session. Returns final URL."""
    r = session.get(AUTH + '/login', params={'service': SERVICE}, timeout=20)
    salt = re.search(r'id="pwdEncryptSalt" value="([^"]*)"', r.text)
    execution = re.search(r'name="execution" value="([^"]*)"', r.text)
    if not salt or not execution:
        raise RuntimeError('login page parse failed')
    need = session.get(AUTH + '/checkNeedCaptcha.htl',
                       params={'username': username}, timeout=20).json()
    if need.get('isNeed'):
        for attempt in range(4):
            if solve_slider(session, debug=attempt >= 2):
                break
            time.sleep(0.5)
        else:
            raise RuntimeError('slider captcha failed')
    data = {
        'username': username,
        'password': encrypt(password, salt.group(1)),
        'captcha': '',
        '_eventId': 'submit',
        'cllt': 'userNameLogin',
        'dllt': 'generalLogin',
        'lt': '',
        'execution': execution.group(1),
    }
    r = session.post(AUTH + '/login', params={'service': SERVICE}, data=data,
                     timeout=25, allow_redirects=True)
    if 'authserver/login' in r.url and r.status_code != 200:
        raise RuntimeError('login rejected: %s' % r.status_code)
    if 'authserver/login' in r.url:
        m = re.search(r'id="showErrorTip"[^>]*>(?:<span>)?([^<]*)', r.text)
        raise RuntimeError('login failed: %s' % (m.group(1).strip() if m else 'unknown'))
    return r.url


if __name__ == '__main__':
    import os
    import sys
    user = os.environ.get('HQU_USER', '')
    pwd = os.environ.get('HQU_PASS', '')
    if not user or not pwd:
        print('set HQU_USER / HQU_PASS')
        sys.exit(1)
    s = requests.Session()
    s.headers.update({'User-Agent': UA})
    t0 = time.time()
    url = login(s, user, pwd)
    print('LOGIN OK ->', url, 'in %.1fs' % (time.time() - t0))
    print('cookies:', [c.name for c in s.cookies])
    r = s.get('https://jwapp.hqu.edu.cn/jwapp/sys/emaphome/portal/index.do', timeout=20)
    with open('portal.html', 'w', encoding='utf-8') as f:
        f.write(r.text)
    print('portal saved:', r.status_code, len(r.text))
