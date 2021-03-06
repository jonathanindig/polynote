import { UIEvent, UIEventTarget } from './ui_event.js'

export class SelectionChangedEvent extends UIEvent {
    constructor(changedFromEl, changedToEl, changedFromIndex, changedToIndex) {
        super('change', { changedFromEl: changedFromEl, changedToEl: changedToEl, changedFromIndex: changedFromIndex, changedToIndex: changedToIndex });
    }

    get newIndex() { return this.detail.changedToIndex }
    get oldIndex() { return this.detail.changedFromIndex }
    get newValue() { return this.detail.changedToEl.value }
    get oldValue() { return this.detail.changedFromEl.value }
}

export class FakeSelect extends UIEventTarget {
    constructor(element) {
        super();
        this.element = element;
        this.command = element.getAttribute('command');

        this.addEventListener('mousedown', evt => evt.preventDefault());

        Object.defineProperty(element, 'selectedIndex', {
            get: () => this.selectedIndex,
            set: value => this.selectedIndex = value
        });

        Object.defineProperty(element, 'options', {
            get: () => this.options
        });

        const marker = this.element.querySelector('.marker');
        if (marker) {
            marker.addEventListener('mousedown', evt => {
                if (!this.isOpen) {
                    this.opener = this.selectedElement;
                    this.moved = false;
                    this.expand();
                } else {
                    this.collapse();
                }
            });
        }

        this.options = [...element.getElementsByTagName("button")];
        for (const option of this.options) {
            if (option.classList.contains('selected')) {
                this.selectedElement = option;
            }

            this.setupOption(option);
        }

        if (!this.selectedElement) {
            this.selectedElement = this.options[0];
        }

        if (this.selectedElement) {
            this.value = this.selectedElement.value;
        }
    }

    setupOption(option) {
        option.addEventListener('mousedown', (evt) => {
            evt.preventDefault();
            evt.cancelBubble = true;

            if (!this.isOpen) {
                this.opener = evt.target;
                this.moved = false;
                this.expand();
            } else {
                this.moved = true;
            }
        });

        option.addEventListener('mousemove', (evt) => {
            if (evt.target !== this.opener && evt.target !== this.selectedElementCopy) {
                this.moved = true;
            }
        });

        option.addEventListener('mouseup', (evt) => {
            evt.preventDefault();
            evt.cancelBubble = true;

            if (evt.target !== this.selectedElementCopy || this.moved) {
                if (evt.target !== this.selectedElementCopy)
                    this.setSelectedElement(evt.target);
                this.collapse();
            }
        });
    }

    get selectedIndex() {
        return this.options.indexOf(this.selectedElement);
    }

    set selectedIndex(idx) {
        this.setSelectedElement(this.options[idx]);
    }

    setSelectedElement(el, noEvent) {
        const prevIndex = this.selectedIndex;
        const prevEl = this.selectedElement;

        if (el === this.selectedElementCopy) {
            return this.setSelectedElement(this.selectedElement);
        }


        if (this.selectedElement) {
            this.selectedElement.classList.remove('selected');
        }

        this.selectedElement = el;
        this.selectedElement.classList.add('selected');

        if (this.value !== this.selectedElement.value) {
            this.value = this.selectedElement.value;
            const newIndex = this.options.indexOf(el);
            if (!noEvent) {
                let event = new SelectionChangedEvent(prevEl, el, prevIndex, newIndex);
                this.dispatchEvent(event);
            }
        }
    }

    expand() {
        if (this.selectedElement) {
            const selectedEl = this.selectedElement;
            this.selectedElementCopy = selectedEl.cloneNode(true);
            this.setupOption(this.selectedElementCopy);
            this.element.insertBefore(this.selectedElementCopy, this.options[0]);
        }
        this.element.classList.add('open');
    }

    collapse() {
        if (this.selectedElementCopy) {
            this.selectedElementCopy.parentNode.removeChild(this.selectedElementCopy);  // TODO: remove event listeners first?
            this.selectedElementCopy = null;
        }

        this.opener = null;
        this.moved = false;
        this.element.classList.remove('open');
    }

    toggle() {
        this.element.classList.toggle('open');
    }

    get isOpen() {
        return this.element.classList.contains('open');
    }

    setState(state) {
        if (state === '') {
            this.setSelectedElement(this.options[0], true);
        } else {
            for (const option of this.options) {
                if (option.value === state) {
                    this.setSelectedElement(option, true);
                    return;
                }
            }
        }
    }

}