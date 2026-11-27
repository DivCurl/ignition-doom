/*
 * DOOM on Ignition: mouse input for the play pages (single-player and match).
 *
 * Pointer lock + relative movement, as in browser FPS games and source ports:
 *   - click the game view to capture the mouse; Esc releases it (browser rule)
 *   - horizontal movement turns, vertical movement looks (when mouselook is on;
 *     otherwise the engine uses it the vanilla way)
 *   - left button fires, right button uses, wheel changes weapon
 *     (wheel down = next, wheel up = previous, GZDoom's default bindings)
 *
 * The page defines, before loading this file:
 *   container            the focusable game element
 *   _sendInputLine(s)    sends one input message (WebSocket, or POST fallback)
 *   _wsOpen()            true when the WebSocket is connected
 *   _canCapture()        false while an overlay (e.g. the match lobby) is up
 *   gameEnded            set once the game is over
 *
 * Message format (see InputMessages.java): "m:dx,dy,buttons,wheel", where dy is
 * positive when the mouse is pushed away (DOOM's convention), and
 * "opt:xhair=0|1" for the crosshair.
 *
 * Per-browser settings are kept in localStorage 'doomMouse' (written by the
 * landing page): {sens: number, invert: bool, crosshair: bool}.
 */
(function () {
  'use strict';

  var cfg = (function () {
    var c = {};
    try { c = JSON.parse(localStorage.getItem('doomMouse') || '{}') || {}; } catch (e) { c = {}; }
    var sens = parseFloat(c.sens);
    return {
      sens: sens > 0 && sens <= 10 ? sens : 1.0,
      invert: !!c.invert,
      crosshair: !!c.crosshair
    };
  })();

  var dx = 0, dy = 0, wheel = 0, buttons = 0, sentButtons = 0;
  var wheelAcc = 0, flushPending = false;

  function locked() { return document.pointerLockElement === container; }

  function flush() {
    flushPending = false;
    if (gameEnded) return;
    var ix = Math.trunc(dx), iy = Math.trunc(dy);
    if (ix === 0 && iy === 0 && wheel === 0 && buttons === sentButtons) return;
    dx -= ix; dy -= iy;
    var w = wheel; wheel = 0;
    sentButtons = buttons;
    _sendInputLine('m:' + ix + ',' + iy + ',' + buttons + ',' + w);
  }

  // Over the WebSocket, send once per animation frame. Over the POST fallback,
  // batch to ~20 requests a second so the server isn't flooded.
  function queue() {
    if (flushPending) return;
    flushPending = true;
    if (_wsOpen()) requestAnimationFrame(flush);
    else setTimeout(flush, 50);
  }

  // DOM button numbers -> DOOM bits: left 1, right 2, middle 4.
  function bit(b) { return b === 0 ? 1 : b === 2 ? 2 : b === 1 ? 4 : 0; }

  // ── Capture prompt ────────────────────────────────────────────────────────
  var hint = document.createElement('div');
  hint.id = 'mouseHint';
  hint.textContent = 'Click to capture the mouse — Esc releases it';
  hint.style.cssText = 'position:absolute;top:10px;left:50%;transform:translateX(-50%);'
    + 'background:rgba(0,0,0,0.65);color:#ddd;font:12px monospace;padding:4px 12px;'
    + 'border:1px solid #444;pointer-events:none;z-index:50;white-space:nowrap';
  container.appendChild(hint);

  function updateHint() {
    hint.style.display = (locked() || gameEnded || !_canCapture()) ? 'none' : '';
  }
  setInterval(updateHint, 500);
  updateHint();

  document.addEventListener('pointerlockchange', function () {
    if (!locked() && buttons !== 0) {
      buttons = 0;   // never leave the gun firing after Esc
      queue();
    }
    updateHint();
  });

  // ── Movement ──────────────────────────────────────────────────────────────
  document.addEventListener('mousemove', function (e) {
    if (!locked()) return;
    dx += e.movementX * cfg.sens;
    dy += -e.movementY * cfg.sens * (cfg.invert ? -1 : 1);
    queue();
  });

  // ── Buttons ───────────────────────────────────────────────────────────────
  container.addEventListener('mousedown', function (e) {
    if (!locked()) {
      // This click only captures the mouse; it does not fire.
      if (!gameEnded && _canCapture() && container.requestPointerLock) {
        container.focus();
        var p = container.requestPointerLock();
        if (p && p.catch) p.catch(function () {});
      }
      return;
    }
    e.preventDefault();
    buttons |= bit(e.button);
    queue();
  });

  document.addEventListener('mouseup', function (e) {
    var b = bit(e.button);
    if (buttons & b) {
      buttons &= ~b;
      queue();
    }
  });

  container.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  // ── Wheel: one weapon step per notch ──────────────────────────────────────
  // A mouse notch is ~100px (Chrome) or 3 lines (Firefox); trackpads send many
  // small deltas, which are added up until they make a whole notch.
  container.addEventListener('wheel', function (e) {
    e.preventDefault();
    if (gameEnded) return;
    var d = e.deltaY * (e.deltaMode === 1 ? 33 : e.deltaMode === 2 ? 100 : 1);
    if (Math.abs(d) >= 50) {
      wheelAcc = 0;
      wheel += d > 0 ? 1 : -1;
    } else {
      wheelAcc += d;
      if (Math.abs(wheelAcc) < 100) return;
      wheel += wheelAcc > 0 ? 1 : -1;
      wheelAcc = 0;
    }
    queue();
  }, { passive: false });

  window.addEventListener('blur', function () {
    if (buttons !== 0) { buttons = 0; queue(); }
  });

  // ── Options ───────────────────────────────────────────────────────────────
  // Called by the page when its input channel (WebSocket or POST fallback) is ready.
  window._sendOpts = function () {
    _sendInputLine('opt:xhair=' + (cfg.crosshair ? 1 : 0));
  };
})();
