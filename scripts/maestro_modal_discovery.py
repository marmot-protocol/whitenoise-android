"""Find production modal owners from real Kotlin calls, independently of UI name suffixes."""

import re

try:
    from scripts.check_manual_test_guide import kotlin_code_mask, consume_kotlin_type_parameters, consume_kotlin_string
except ModuleNotFoundError:
    from check_manual_test_guide import kotlin_code_mask, consume_kotlin_type_parameters, consume_kotlin_string


MODAL_CONSTRUCTORS = frozenset({
    'AlertDialog', 'BasicAlertDialog', 'Dialog', 'Popup', 'DropdownMenu', 'ModalBottomSheet',
    'WhiteNoiseAlertDialog', 'WhiteNoiseDropdownMenu', 'WhiteNoiseModalBottomSheet',
    'KeyboardSafePopup', 'ConfirmDialog', 'ChoiceDialog', 'SpeechChoiceDialog', 'PlaybackDialog',
})
IDENTIFIER = r'(?:[A-Za-z_][A-Za-z0-9_]*|`[^`\n]+`)'
DECLARATION = re.compile(r'(?P<qualified>' + IDENTIFIER + r'(?:\s*\.\s*' + IDENTIFIER + r')*)\s*\(')


def balanced_end(code, start, opening, closing):
    """Retain exact offsets while consuming a nested Kotlin parameter or block body."""
    depth = 0
    for offset in range(start, len(code)):
        if code[offset] == opening:
            depth += 1
        elif code[offset] == closing:
            depth -= 1
            if depth == 0:
                return offset + 1
    raise ValueError('Unbalanced Kotlin modal-owner declaration')


def expression_code(text):
    """Keep one inert value token for literals so a trailing operator cannot swallow the next UI."""
    masked = list(kotlin_code_mask(text))
    cursor = 0
    while cursor < len(text):
        if text.startswith('//', cursor):
            end = text.find('\n', cursor)
            cursor = end if end >= 0 else len(text)
        elif text.startswith('/*', cursor):
            cursor += 2
            depth = 1
            while cursor < len(text) and depth:
                if text.startswith('/*', cursor):
                    depth += 1
                    cursor += 2
                elif text.startswith('*/', cursor):
                    depth -= 1
                    cursor += 2
                else:
                    cursor += 1
        elif text[cursor] in '\"\'':
            masked[cursor] = '0'
            cursor = consume_kotlin_string(text, cursor)
        else:
            cursor += 1
    return ''.join(masked)


def expression_end(code, start, limit):
    """Stop a completed expression at its own line, retaining balanced lambdas and chained calls."""
    depths = {'(': 0, '[': 0, '{': 0}
    closers = {')': '(', ']': '[', '}': '{'}
    started = False
    last = ''
    for cursor in range(start, limit):
        char = code[cursor]
        if char in depths:
            depths[char] += 1
        elif char in closers:
            if depths[closers[char]] == 0:
                return cursor
            depths[closers[char]] -= 1
        if not any(depths.values()) and started:
            if char == ';':
                return cursor
            if char == '\n':
                following = code[cursor + 1:limit].lstrip()
                if not (last in '.=+-*/&|?:,' or following.startswith(('.', '?', '{', 'else'))):
                    return cursor
        if not char.isspace():
            started = True
            last = char
    return limit


def function_ranges(code):
    """Find block and expression owners; parameter defaults belong to their real function."""
    functions = []
    for token in re.finditer(r'\bfun\s+', code):
        start = token.end()
        if code[start:start + 1] == '<':
            start = consume_kotlin_type_parameters(code, start)
            if start is None:
                raise ValueError('Unparsed Kotlin modal-owner type parameters')
            while start < len(code) and code[start].isspace():
                start += 1
        name = DECLARATION.match(code, start)
        if not name:
            continue
        parameters_end = balanced_end(code, name.end() - 1, '(', ')')
        functions.append({'start': token.start(), 'parameters_open': name.end() - 1,
                          'name': re.sub(r'\s*\.\s*', '.', name['qualified']).replace('`', ''),
                          'parameters_end': parameters_end})
    for index, function in enumerate(functions):
        limit = functions[index + 1]['start'] if index + 1 < len(functions) else len(code)
        cursor = function['parameters_end']
        # Return types contain parentheses and arrows. A body begins only outside them.
        depth = 0
        while cursor < limit:
            char = code[cursor]
            if char in '([':
                depth += 1
            elif char in ')]':
                depth -= 1
            elif depth == 0 and char in '{=':
                break
            cursor += 1
        if cursor >= limit:
            function['end'] = function['parameters_end']
        elif code[cursor] == '{':
            function['end'] = balanced_end(code, cursor, '{', '}')
        else:
            function['end'] = expression_end(code, cursor + 1, limit)
    return functions


def modal_owners(text):
    """Every real modal call needs a named owner; declarations/comments/literals grant no credit."""
    code = expression_code(text)
    functions = function_ranges(code)
    declaration_parameters = {function['parameters_open'] for function in functions}
    names = set()
    calls = re.compile(r'\b(' + '|'.join(sorted(MODAL_CONSTRUCTORS)) + r')\s*\(')
    for call in calls.finditer(code):
        if call.end() - 1 in declaration_parameters or code[max(0, call.start() - 1):call.start()] == '@':
            continue
        enclosing = [function for function in functions if function['start'] <= call.start() < function['end']]
        if not enclosing:
            line = text.count('\n', 0, call.start()) + 1
            raise ValueError(f'Modal call has no audited named owner at line {line}: {call.group(1)}')
        owner = min(enclosing, key=lambda function: function['end'] - function['start'])
        names.add(owner['name'])
    return names
