// Just enough of a DOM for lil-gui to build a panel under Node, so the preset tests can save
// and load a real lil-gui panel. Elements keep their children and attributes; events are
// recorded but never fired. Nothing is laid out or drawn.

class FakeElement {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase();
    this.children = [];
    this.childNodes = this.children;
    this.parentNode = null;
    this.style = {};
    this.attributes = {};
    this.dataset = {};
    this.listeners = {};
    this.innerHTML = '';
    this.textContent = '';
    this.value = '';
    this.checked = false;
    this.disabled = false;
    const classes = new Set();
    this.classList = {
      add: (...c) => c.forEach((x) => classes.add(x)),
      remove: (...c) => c.forEach((x) => classes.delete(x)),
      toggle: (c, force) => {
        const on = force === undefined ? !classes.has(c) : force;
        if (on) classes.add(c); else classes.delete(c);
        return on;
      },
      contains: (c) => classes.has(c),
    };
  }
  appendChild(child) { child.remove?.(); this.children.push(child); child.parentNode = this; return child; }
  insertBefore(child, ref) {
    child.remove?.();
    const i = ref ? this.children.indexOf(ref) : -1;
    if (i < 0) this.children.push(child); else this.children.splice(i, 0, child);
    child.parentNode = this;
    return child;
  }
  removeChild(child) { const i = this.children.indexOf(child); if (i >= 0) this.children.splice(i, 1); child.parentNode = null; return child; }
  remove() { this.parentNode?.removeChild(this); }
  get firstChild() { return this.children[0] ?? null; }
  get lastChild() { return this.children[this.children.length - 1] ?? null; }
  setAttribute(k, v) { this.attributes[k] = String(v); }
  getAttribute(k) { return this.attributes[k] ?? null; }
  removeAttribute(k) { delete this.attributes[k]; }
  toggleAttribute(k, force) { const on = force ?? !(k in this.attributes); if (on) this.attributes[k] = ''; else delete this.attributes[k]; return on; }
  addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
  removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] ?? []).filter((f) => f !== fn); }
  querySelector() { return null; }
  querySelectorAll() { return []; }
  getBoundingClientRect() { return { left: 0, top: 0, width: 100, height: 20, right: 100, bottom: 20 }; }
  focus() {}
  blur() {}
  contains() { return false; }
  get clientHeight() { return 0; }
  get scrollHeight() { return 0; }
}

/** Installs a fake `document` and `window` on globalThis (only if there is no real one). */
export function installFakeDom() {
  if (globalThis.document) return;
  const document = {
    createElement: (tag) => new FakeElement(tag),
    createTextNode: (text) => Object.assign(new FakeElement('#text'), { textContent: text }),
    head: new FakeElement('head'),
    body: new FakeElement('body'),
    addEventListener() {},
    removeEventListener() {},
    querySelector: () => null,
  };
  globalThis.document = document;
  globalThis.window = globalThis.window ?? Object.assign(globalThis, {
    addEventListener() {}, removeEventListener() {}, matchMedia: () => ({ matches: false, addEventListener() {} }),
  });
}
