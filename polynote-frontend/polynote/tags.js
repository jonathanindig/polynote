'use strict';

function appendContent(el, content) {
    if (!(content instanceof Array)) {
        content = [content];
    }

    for (var item of content) {
        if (typeof item === "string") {
            el.appendChild(document.createTextNode(item));
        } else if (item instanceof Node) {
            el.appendChild(item);
        }
    }
}

export function tag(name, classes, attributes, content) {
    const el = document.createElement(name);
    el.classList.add(...classes);
    for (var attr in attributes) {
        if (attributes.hasOwnProperty(attr)) {
            el.setAttribute(attr, attributes[attr]);
        }
    }

    appendContent(el, content);

    el.attr = function(a, v) {
        el.setAttribute(a, v);
        return el;
    };

    el.attrs = function(obj) {
        for (attr in obj) {
            if (obj.hasOwnProperty(attr)) {
                el.setAttribute(attr, obj[attr]);
            }
        }
        return el;
    };

    el.withId = function(id) {
        el.id = id;
        return el;
    };

    el.click = function(handler) {
      el.addEventListener('click', handler);
      return el;
    };

    el.change = function(handler) {
        el.addEventListener('change', handler);
        return el;
    };

    return el;
}

export function blockquote(classes, content) {
    return tag('blockquote', classes, {}, content);
}

export function para(classes, content) {
    return tag('p', classes, {}, content);
}

export function span(classes, content) {
    return tag('span', classes, {}, content);
}

export function div(classes, content) {
    return tag('div', classes, {}, content);
}

export function button(classes, attributes, content) {
    return tag('button', classes, attributes, content);
}

export function iconButton(classes, title, icon, alt) {
    classes.push('icon-button');
    return button(classes, {title: title}, [
        span(['icon', 'fas'], [icon]),
        span(['alt'], [alt])
    ]);
}

export function textbox(classes, placeholder, value) {
    const input = tag('input', classes, {type: 'text', placeholder: placeholder}, []);
    if (value) {
        input.value = value;
    }
    return input;
}

export function dropdown(classes, options) {
    let opts = [];

    if (options instanceof Array) {
        for (const i of options) {
            opts.push(tag('option', [], {}, [opts[i]]));
        }
    } else {
        for (const value in options) {
            if (options.hasOwnProperty(value)) {
                opts.push(tag('option', [], {value: value}, [options[value]]));
            }
        }
    }

    return tag('select', classes, {}, opts);
}

export function checkbox(classes, label, value) {
    const attrs = {type:'checkbox'};
    if (value)
        attrs.checked = 'checked';
    return tag('label', classes, {}, [
        tag('input', [], attrs, []),
        span([], [label])
    ]);
}

export function h2(classes, content) {
    return tag('h2', classes, {}, content)
}

export function h3(classes, content) {
    return tag('h3', classes, {}, content);
}

export function h4(classes, content) {
    return tag('h4', classes, {}, content);
}

/**
 * @param contentSpec An object specifying the following properties (all optional)
 *                      - header: An array of strings representing the table header labels
 *                      - classes: An array of strings representing class names for the th/td elements of each column
 *                      - rows: An array of arrays, where each inner array is a row of content elements of each column
 *                              Can also be an array of objects, where each object has keys specifying a class name from
 *                              the classes array.
 *                      - rowHeading: If true, first element of each row will be a <th scope="row"> rather than <td>.
 *                      - addToTop: If true, addRow will add rows to the top of the body, otherwise to the bottom.
 * The resulting table element will have an additional addRow function, which appends a row.
 */
export function table(classes, contentSpec) {
    const colClass = (col) => contentSpec.classes ? contentSpec.classes[col] : '';
    const heading = contentSpec.header ? [tag('thead', [], {}, [
            tag('tr', [], [], contentSpec.header.map((c, i) => tag('th', [colClass(i)], {}, c instanceof Array ? c : [c])))
        ])] : [];

    function mkTR(row) {
        const rowIsArray = row instanceof Array;

        const contentArrays =
            rowIsArray ? row.map(content => content instanceof Array ? content : [content])
                       : contentSpec.classes.map(cls => row[cls]).map(content => content instanceof Array ? content : [content])


        const propCells = rowIsArray ? [] : {};
        const cells = contentArrays.map((c, i) => {
            const cell = i === 0 && contentSpec.rowHeading ? tag('th', [colClass(i)], {scope: 'row'}, c) : tag('td', [colClass(i)], {}, c);
            if (rowIsArray) {
                propCells[i] = cell;
            } else {
                propCells[contentSpec.classes[i]] = cell;
            }
            return cell;
        });

        const tr = tag('tr', [], {}, cells);

        tr.row = row;
        tr.propCells = propCells;
        row.__el = tr;

        tr.updateValues = (props) => {
            for (var prop in props) {
                if (props.hasOwnProperty(prop)) {
                    const value = props[prop];
                    tr.row[prop] = value;
                    const cell = propCells[prop];
                    cell.innerHTML = "";
                    appendContent(cell, value);
                }
            }
        };

        return tr;
    }

    const body = tag(
        'tbody', [], [],
        contentSpec.rows ? contentSpec.rows.map(mkTR) : []
    );

    const table = tag('table', classes, {}, [
        ...heading, body
    ]);

    table.addRow = (row) => {
        const rowEl = mkTR(row);
        if (contentSpec.addToTop)
            body.insertBefore(rowEl, body.firstChild);
        else
            body.appendChild(rowEl);
        return rowEl;
    };

    table.findRows = (props) => table.findRowsBy((row) => {
        for (var prop in props) {
            if (props.hasOwnProperty(prop)) {
                if (row[prop] !== props[prop])
                    return false;
            }
        }
        return true;
    });

    table.findRowsBy = (fn) => {
        const matches = [];
        [...table.querySelectorAll('tbody tr')].forEach(tr => {
            if (fn(tr.row)) {
                matches.push(tr);
            }
        });
        return matches;
    };

    return table;
}