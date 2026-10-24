/**
 * FastRead Polyfills for Chromium 30 / Android 4.4.4 KitKat
 */
(function() {
  'use strict';

  // 0. setImmediate polyfill (ensures async tasks and JSZip never stall)
  if (!window.setImmediate) {
    window.setImmediate = function(fn) {
      var args = Array.prototype.slice.call(arguments, 1);
      return setTimeout(function() { fn.apply(null, args); }, 0);
    };
    window.clearImmediate = function(id) {
      clearTimeout(id);
    };
  }

  // 1. Object.assign
  if (!Object.assign) {
    Object.assign = function(target) {
      if (target == null) throw new TypeError('Cannot convert undefined or null to object');
      var to = Object(target);
      for (var index = 1; index < arguments.length; index++) {
        var nextSource = arguments[index];
        if (nextSource != null) {
          for (var nextKey in nextSource) {
            if (Object.prototype.hasOwnProperty.call(nextSource, nextKey)) {
              to[nextKey] = nextSource[nextKey];
            }
          }
        }
      }
      return to;
    };
  }

  // 2. Array.prototype methods
  if (!Array.prototype.find) {
    Array.prototype.find = function(predicate) {
      if (this == null) throw new TypeError('Array.prototype.find called on null or undefined');
      if (typeof predicate !== 'function') throw new TypeError('predicate must be a function');
      var list = Object(this);
      var length = list.length >>> 0;
      var thisArg = arguments[1];
      for (var i = 0; i < length; i++) {
        var value = list[i];
        if (predicate.call(thisArg, value, i, list)) return value;
      }
      return undefined;
    };
  }

  if (!Array.prototype.findIndex) {
    Array.prototype.findIndex = function(predicate) {
      if (this == null) throw new TypeError('Array.prototype.findIndex called on null or undefined');
      if (typeof predicate !== 'function') throw new TypeError('predicate must be a function');
      var list = Object(this);
      var length = list.length >>> 0;
      var thisArg = arguments[1];
      for (var i = 0; i < length; i++) {
        if (predicate.call(thisArg, list[i], i, list)) return i;
      }
      return -1;
    };
  }

  if (!Array.prototype.includes) {
    Array.prototype.includes = function(searchElement, fromIndex) {
      var o = Object(this);
      var len = o.length >>> 0;
      if (len === 0) return false;
      var n = fromIndex | 0;
      var k = Math.max(n >= 0 ? n : len - Math.abs(n), 0);
      while (k < len) {
        if (o[k] === searchElement || (typeof o[k] === 'number' && typeof searchElement === 'number' && isNaN(o[k]) && isNaN(searchElement))) return true;
        k++;
      }
      return false;
    };
  }

  // 3. String.prototype methods
  if (!String.prototype.includes) {
    String.prototype.includes = function(search, start) {
      if (typeof start !== 'number') start = 0;
      if (start + search.length > this.length) return false;
      return this.indexOf(search, start) !== -1;
    };
  }

  if (!String.prototype.startsWith) {
    String.prototype.startsWith = function(search, pos) {
      pos = !pos || pos < 0 ? 0 : +pos;
      return this.substring(pos, pos + search.length) === search;
    };
  }

  if (!String.prototype.endsWith) {
    String.prototype.endsWith = function(search, this_len) {
      if (this_len === undefined || this_len > this.length) this_len = this.length;
      return this.substring(this_len - search.length, this_len) === search;
    };
  }

  // 4. DOM Element methods
  if (!Element.prototype.matches) {
    Element.prototype.matches = Element.prototype.webkitMatchesSelector ||
      Element.prototype.mozMatchesSelector ||
      Element.prototype.msMatchesSelector ||
      function(s) {
        var matches = (this.document || this.ownerDocument).querySelectorAll(s), i = matches.length;
        while (--i >= 0 && matches.item(i) !== this) {}
        return i > -1;
      };
  }

  if (!Element.prototype.closest) {
    Element.prototype.closest = function(s) {
      var el = this;
      while (el && el.nodeType === 1) {
        if (el.matches(s)) return el;
        el = el.parentElement || el.parentNode;
      }
      return null;
    };
  }

  if (!Element.prototype.remove) {
    Element.prototype.remove = function() {
      if (this.parentNode) this.parentNode.removeChild(this);
    };
  }

  if (!Element.prototype.append) {
    Element.prototype.append = function() {
      for (var i = 0; i < arguments.length; i++) {
        var node = arguments[i];
        if (typeof node === 'string') {
          this.appendChild(document.createTextNode(node));
        } else if (node && node.nodeType) {
          this.appendChild(node);
        }
      }
    };
  }

  if (!Element.prototype.replaceChildren) {
    Element.prototype.replaceChildren = function() {
      while (this.firstChild) {
        this.removeChild(this.firstChild);
      }
      for (var i = 0; i < arguments.length; i++) {
        var node = arguments[i];
        if (typeof node === 'string') {
          this.appendChild(document.createTextNode(node));
        } else if (node && node.nodeType) {
          this.appendChild(node);
        }
      }
    };
  }

  if (!Element.prototype.replaceWith) {
    Element.prototype.replaceWith = function() {
      var parent = this.parentNode;
      if (!parent) return;
      for (var i = 0; i < arguments.length; i++) {
        var node = arguments[i];
        if (typeof node === 'string') {
          parent.insertBefore(document.createTextNode(node), this);
        } else if (node && node.nodeType) {
          parent.insertBefore(node, this);
        }
      }
      parent.removeChild(this);
    };
  }

  // 5. TextDecoder Polyfill (UTF-8, Windows-1251, ASCII)
  var CP1251_MAP = (
    '\u0402\u0403\u201A\u0453\u201E\u2026\u2020\u2021\u20AC\u2030\u0409\u2039\u040A\u040C\u040B\u040F' +
    '\u0452\u2018\u2019\u201C\u201D\u2022\u2013\u2014\uFFFD\u2122\u0459\u203A\u045A\u045C\u045B\u045F' +
    '\u00A0\u040E\u045E\u0408\u00A4\u0490\u00A6\u00A7\u0401\u00A9\u0404\u00AB\u00AC\u00AD\u00AE\u0407' +
    '\u00B0\u00B1\u0406\u0456\u0491\u00B5\u00B6\u00B7\u0451\u2116\u0454\u00BB\u0458\u0405\u0455\u0457' +
    '\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u0419\u041A\u041B\u041C\u041D\u041E\u041F' +
    '\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042A\u042B\u042C\u042D\u042E\u042F' +
    '\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043A\u043B\u043C\u043D\u043E\u043F' +
    '\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044A\u044B\u044C\u044D\u044E\u044F'
  );

  function decodeUtf8(bytes, fatal) {
    var out = '';
    var i = 0;
    var len = bytes.length;
    while (i < len) {
      var b1 = bytes[i++];
      if (b1 < 0x80) {
        out += String.fromCharCode(b1);
      } else if ((b1 & 0xE0) === 0xC0) {
        if (i >= len) { if (fatal) throw new Error('Truncated UTF-8'); out += '\uFFFD'; break; }
        var b2 = bytes[i++];
        if ((b2 & 0xC0) !== 0x80) { if (fatal) throw new Error('Invalid UTF-8'); out += '\uFFFD'; continue; }
        out += String.fromCharCode(((b1 & 0x1F) << 6) | (b2 & 0x3F));
      } else if ((b1 & 0xF0) === 0xE0) {
        if (i + 1 >= len) { if (fatal) throw new Error('Truncated UTF-8'); out += '\uFFFD'; break; }
        var b2e = bytes[i++];
        var b3e = bytes[i++];
        if ((b2e & 0xC0) !== 0x80 || (b3e & 0xC0) !== 0x80) { if (fatal) throw new Error('Invalid UTF-8'); out += '\uFFFD'; continue; }
        out += String.fromCharCode(((b1 & 0x0F) << 12) | ((b2e & 0x3F) << 6) | (b3e & 0x3F));
      } else if ((b1 & 0xF8) === 0xF0) {
        if (i + 2 >= len) { if (fatal) throw new Error('Truncated UTF-8'); out += '\uFFFD'; break; }
        var b2f = bytes[i++];
        var b3f = bytes[i++];
        var b4f = bytes[i++];
        if ((b2f & 0xC0) !== 0x80 || (b3f & 0xC0) !== 0x80 || (b4f & 0xC0) !== 0x80) {
          if (fatal) throw new Error('Invalid UTF-8'); out += '\uFFFD'; continue;
        }
        var cp = (((b1 & 0x07) << 18) | ((b2f & 0x3F) << 12) | ((b3f & 0x3F) << 6) | (b4f & 0x3F)) - 0x10000;
        out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
      } else {
        if (fatal) throw new Error('Invalid UTF-8 sequence byte');
        out += '\uFFFD';
      }
    }
    return out;
  }

  function decodeWindows1251(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      var b = bytes[i];
      if (b < 0x80) {
        out += String.fromCharCode(b);
      } else {
        out += CP1251_MAP.charAt(b - 0x80);
      }
    }
    return out;
  }

  function decodeAscii(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      out += String.fromCharCode(bytes[i] & 0x7F);
    }
    return out;
  }

  function FastTextDecoder(encoding, options) {
    this.encoding = (encoding || 'utf-8').toLowerCase().replace(/^cs/, '').replace(/-/g, '');
    this.fatal = !!(options && options.fatal);
  }

  FastTextDecoder.prototype.decode = function(view) {
    if (!view) return '';
    var bytes;
    if (view instanceof ArrayBuffer) {
      bytes = new Uint8Array(view);
    } else if (view.buffer instanceof ArrayBuffer) {
      bytes = new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
    } else if (Array.isArray(view)) {
      bytes = view;
    } else {
      bytes = new Uint8Array(view);
    }

    var enc = this.encoding;
    if (enc === 'utf8' || enc === 'unicode11utf8') {
      return decodeUtf8(bytes, this.fatal);
    }
    if (enc === 'windows1251' || enc === 'cp1251' || enc === '1251') {
      return decodeWindows1251(bytes);
    }
    if (enc === 'ascii' || enc === 'usascii') {
      return decodeAscii(bytes);
    }
    // Fallback to UTF-8
    return decodeUtf8(bytes, false);
  };

  // Only assign if window.TextDecoder doesn't exist or doesn't support windows-1251
  var needsTextDecoder = false;
  if (typeof window.TextDecoder === 'undefined') {
    needsTextDecoder = true;
  } else {
    try {
      new window.TextDecoder('windows-1251');
    } catch (e) {
      needsTextDecoder = true;
    }
  }

  if (needsTextDecoder) {
    window.TextDecoder = FastTextDecoder;
  }
})();
