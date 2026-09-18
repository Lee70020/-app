(function () {
  // Auto-login on the HQU unified-auth login page (id.hqu.edu.cn/authserver).
  // Injected by the app right after the page finishes loading.
  // Credentials are substituted by Java before injection (__USER__/__PASS__).
  //
  // v3 changes (fixes "滑块通过后仍进不去教务" on phones):
  //  - The server serves a DIFFERENT DOM for mobile User-Agents:
  //      desktop: form#pwdFromId, plaintext input[name=passwordText]
  //      mobile : form#loginFromId, plaintext input[name=userPassword]
  //    v2 only looked for #pwdFromId, so the injection was a no-op on phones.
  //    Now the form is located via the #pwdEncryptSalt input, which exists in
  //    both layouts, so the script works on either.
  //  - The mobile page has TWO forms sharing id="loginFromId"; its own script
  //    patches only the first (hidden dynamic-code) one with "?service=", so
  //    the password form posts without a service and never reaches jwapp.
  //    We now append ?service= ourselves when it is missing.
  //  - Submits through HTMLFormElement.prototype.submit to avoid a shadowed
  //    or duplicated submit control, and reports precise diagnostics.
  //
  // v4 changes (0.2.3 device diagnostics: "no_form / salt=false"):
  //  - The app injects on the FIRST onPageFinished, which can fire before
  //    the DOM is parsed (device log showed salt=false, with the real page
  //    arriving one second later). The script now WAITS for the form
  //    (up to 20 x 500ms) instead of giving up immediately.
  //  - no_form releases __LOGIN_RUNNING so a later re-injection can retry.
  // v5: a native attempt token prevents stale async work from submitting or reporting.
  var ATTEMPT = __ATTEMPT__;
  if (window.__LOGIN_RUNNING && window.__LOGIN_ATTEMPT === ATTEMPT) return;
  window.__LOGIN_ATTEMPT = ATTEMPT;
  window.__LOGIN_RUNNING = true;
  var alive = function () { return window.__LOGIN_ATTEMPT === ATTEMPT; };

  var USER = __USER__, PASS = __PASS__;
  window.__LOGIN_RESULT = null;
  var done = function (obj) {
    if (!alive()) return;
    obj.attempt = ATTEMPT;
    if (!obj.ok) window.__LOGIN_RUNNING = false;
    try { window.__LOGIN_RESULT = JSON.stringify(obj); } catch (e) {}
  };
  var serverTip = function () {
    var ids = ['showErrorTip', 'formErrorTip2'];
    for (var i = 0; i < ids.length; i++) {
      var el = document.getElementById(ids[i]);
      if (el && el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden') {
        var text = (el.textContent || '').trim();
        if (text) return text.replace(/\s+/g, ' ').slice(0, 160);
      }
    }
    return '';
  };
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  (async function () {
    try {
      // 1) Locate the password-login form. #pwdEncryptSalt exists in both the
      //    desktop (#pwdFromId) and mobile (#loginFromId) layouts. The first
      //    onPageFinished can fire before the DOM is parsed, so poll for the
      //    form instead of failing immediately (0.2.3 device log: salt=false).
      var saltEl = null, form = null, waited = 0;
      while (waited < 20) {
        if (!alive()) return;
        saltEl = document.getElementById('pwdEncryptSalt');
        form = document.getElementById('pwdFromId');
        if (!form && saltEl) { try { form = saltEl.form || saltEl.closest('form'); } catch (e) {} }
        if (form && saltEl) break;
        waited++; await sleep(500);
      }
      if (!alive()) return;
      if (!form || !saltEl) {
        done({ ok: false, stage: 'no_form', detail: 'salt=' + !!saltEl + ' path=' + location.pathname + ' wait=' + waited });
        return;
      }

      if (!alive()) return;
      var initialTip = serverTip();
      if (initialTip) { done({ok:false, stage:'server_rejected', detail:initialTip}); return; }

      // 2) Make sure the form posts back with ?service=… (mobile leaves the
      //    password form unpatched because two forms share id=loginFromId).
      var svc = '';
      try { svc = new URLSearchParams(location.search).get('service') || ''; } catch (e) {}
      var rawAction = form.getAttribute('action') || '';
      if (svc && rawAction.indexOf('service=') === -1) {
        form.setAttribute('action', rawAction +
          (rawAction.indexOf('?') === -1 ? '?' : '&') + 'service=' + encodeURIComponent(svc));
      }
      var target = new URL(form.action, location.href);
      if (target.hostname === 'id.hqu.edu.cn' && target.protocol === 'http:' &&
          (!target.port || target.port === '80') && !target.username && !target.password) {
        target.protocol = 'https:'; target.port = '';
      }
      if (target.protocol !== 'https:' || target.hostname !== 'id.hqu.edu.cn' ||
          (target.port && target.port !== '443') || target.username || target.password ||
          target.pathname !== '/authserver/login') {
        done({ok:false,stage:'invalid_action'}); return;
      }
      form.action = target.href;


      var fUser = form.querySelector('input[name=username]');
      var fPlain = form.querySelector('input[name=passwordText]') ||
                   form.querySelector('input[name=userPassword]');
      var fEnc = form.querySelector('input[name=password]');
      var fCap = form.querySelector('input[name=captcha]');
      if (!fUser || !fEnc) {
        done({ ok: false, stage: 'no_form', 
               detail: 'user=' + !!fUser + ' enc=' + !!fEnc + ' wait=' + waited });
        return;
      }

      // 3) Fill the visible fields first so manual fallback stays possible.
      fUser.value = USER;
      if (fPlain) fPlain.value = PASS;

      // 4) Captcha (slider mode on this deployment).
      var need = { isNeed: false };
      try {
        need = await fetch('/authserver/checkNeedCaptcha.htl?username=' +
          encodeURIComponent(USER)).then(function (x) { return x.json(); });
      } catch (e) { /* assume not needed */ }
      if (!alive()) return;
      if (need.isNeed) {
        var solved = false, detail = 'none';
        for (var attempt = 0; attempt < 2 && !solved; attempt++) {
          var r = await solveSlider();
          if (!alive()) return;
          solved = r.solved; detail = r.detail;
          if (!solved) await sleep(500);
        }
        if (!solved) {
          done({ ok: false, stage: 'captcha',  detail: detail });
          return;
        }
      }

      // 5) Mirror the page's own checkForm(): encrypt into the hidden
      //    #saltPassword, disable the plaintext field, clear the captcha.
      if (!alive()) return;
      fEnc.value = encryptPassword(PASS, saltEl.value);
      if (fPlain) fPlain.disabled = true;
      if (fCap) fCap.value = '';

      // A form remaining in the old document is not proof of rejection: navigation
      // can take a minute on mobile networks. Only an explicit visible error is final.
      var watch = function () {
        if (!alive() || !window.__LOGIN_RUNNING) return;
        var tip = serverTip();
        if (tip) { done({ok:false,stage:'server_rejected',detail:tip}); return; }
        done({ok:true,stage:'waiting_redirect'});
        setTimeout(watch,5000);
      };
      setTimeout(watch,10000);
      done({ok:true,stage:'submitted'});
      try {
        HTMLFormElement.prototype.submit.call(form);
      } catch (e) {
        form.submit();
      }
    } catch (e) {
      done({ ok: false, stage: 'error', detail: e && e.name ? e.name : 'Error' });
    }
  })();

  async function solveSlider() {
    if (!alive()) return {solved:false,detail:'cancelled'};
    var tries = 0;
    var d = await fetch('/authserver/common/openSliderCaptcha.htl')
      .then(function (x) { return x.json(); });
    if (!d.bigImage) return { solved: true, tries: 0, detail: 'not-required' };

    // AES key = last 16 bytes of the small PNG payload (same as desktop).
    var bin = atob(d.smallImage), key = '';
    for (var i = bin.length - 16; i < bin.length; i++) key += String.fromCharCode(bin.charCodeAt(i));

    var load = function (src) { return new Promise(function (res) { var im = new Image(); im.onload = function () { res(im); }; im.src = src; }); };
    var big = await load('data:image/png;base64,' + d.bigImage);
    var small = await load('data:image/png;base64,' + d.smallImage);

    var cb = document.createElement('canvas'); cb.width = big.naturalWidth; cb.height = big.naturalHeight;
    var bctx = cb.getContext('2d'); bctx.drawImage(big, 0, 0);
    var bd = bctx.getImageData(0, 0, cb.width, cb.height).data;
    var cs = document.createElement('canvas'); cs.width = small.naturalWidth; cs.height = small.naturalHeight;
    var sctx = cs.getContext('2d'); sctx.drawImage(small, 0, 0);
    var sd = sctx.getImageData(0, 0, cs.width, cs.height).data;

    // Alpha bounding box of the puzzle piece.
    var x0 = 1e9, x1 = -1, y0 = 1e9, y1 = -1;
    for (var y = 0; y < cs.height; y++) for (var x = 0; x < cs.width; x++) {
      if (sd[(y * cs.width + x) * 4 + 3] > 0) {
        if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
      }
    }
    var pw = x1 - x0 + 1;

    // Piece color mean/std over masked pixels.
    var pSum = 0, pSq = 0, cnt = 0;
    for (var yy = y0; yy <= y1; yy++) for (var xx = x0; xx <= x1; xx++) {
      var pi = (yy * cs.width + xx) * 4;
      if (sd[pi + 3] > 0) for (var c = 0; c < 3; c++) { var v = sd[pi + c]; pSum += v; pSq += v * v; cnt++; }
    }
    cnt = Math.max(3, cnt);
    var pm = pSum / cnt, pv = Math.sqrt(Math.max(0, pSq / cnt - pm * pm));

    // Zero-mean color NCC scan across the big image.
    var bestX = 0, bestV = -2;
    for (var bx = 0; bx <= cb.width - pw; bx++) {
      var wSum = 0, wSq = 0, wn = 0;
      for (yy = y0; yy <= y1; yy++) {
        var pRow = (yy * cs.width + x0) * 4, bRow = (yy * cb.width + bx) * 4;
        for (var px = 0; px < pw; px++) {
          var pj = pRow + px * 4;
          if (sd[pj + 3] > 0) for (c = 0; c < 3; c++) { var w = bd[bRow + px * 4 + c]; wSum += w; wSq += w * w; wn++; }
        }
      }
      if (wn === 0) continue;
      var wm = wSum / wn, wv = Math.sqrt(Math.max(0, wSq / wn - wm * wm));
      if (wv < 1e-6) continue;
      var dot = 0;
      for (yy = y0; yy <= y1; yy++) {
        pRow = (yy * cs.width + x0) * 4; bRow = (yy * cb.width + bx) * 4;
        for (px = 0; px < pw; px++) {
          pj = pRow + px * 4;
          if (sd[pj + 3] > 0) for (c = 0; c < 3; c++) dot += (bd[bRow + px * 4 + c] - wm) * (sd[pj + c] - pm);
        }
      }
      var val = dot / (wv * pv * wn);
      if (val > bestV) { bestV = val; bestX = bx; }
    }
    var est = Math.round(bestX / cb.width * 280);

    var tracks = function (mv) {
      var t = [{ a: 0, b: 0, c: 0 }];
      var n = 20 + Math.floor(Math.random() * 8);
      for (var i = 1; i <= n; i++) {
        var p = i / n, e = p * p * (3 - 2 * p);
        t.push({ a: Math.max(0, Math.round(mv * e + (Math.random() * 2 - 1))),
          b: Math.round(Math.random() * 3 - 1.5),
          c: 20 + Math.floor(Math.random() * 40) });
      }
      t.push({ a: mv, b: 0, c: 20 + Math.floor(Math.random() * 30) });
      return t;
    };

    // Desktop-proven probe order: window around the estimate, then a coarse
    // full-range scan (the estimate can be off by 60+ px on some images).
    var moves = [];
    var offs = [0, -3, 3, -6, 6, -9, 9, -12, 12];
    for (i = 0; i < offs.length; i++) moves.push(est + offs[i]);
    for (var m = 0; m <= 230; m += 10) moves.push(m);

    for (i = 0; i < moves.length; i++) {
      if (!alive()) return {solved:false,detail:'cancelled'};
      var mv = Math.max(0, Math.min(238, moves[i]));
      var payload = JSON.stringify({ canvasLength: 280, moveLength: mv, tracks: tracks(mv) });
      var sign = encryptPassword(payload, key);
      tries++;
      var resp = await fetch('/authserver/common/verifySliderCaptcha.htl', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'sign=' + encodeURIComponent(sign)
      }).then(function (x) { return x.json(); });
      if (resp.errorCode === 1) return { solved: true, tries: tries, detail: 'est=' + est + ' hit#' + tries };
      await sleep(300);
    }
    return { solved: false, tries: tries, detail: 'est=' + est + ' no-hit-in-' + tries };
  }
})();
