#!/usr/bin/env python3
"""Documentation checks: four languages, interlinked switchers, same commands.

    python3 tests/test_docs.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
# "" is the English original; the other three are translations of it.
LANGS = ("", ".es", ".fr", ".de")
FAMILIES = ("README{}.md", "docs/feeder{}.md")
LINK = re.compile(r"\[[^\]]*\]\(([^()\s]+)\)")


def files(template):
    return [ROOT / template.format(lang) for lang in LANGS]


def rel(path):
    return path.relative_to(ROOT).as_posix()


def text(path):
    return path.read_text(encoding="utf-8")


def switcher(path):
    """The link targets on the first line of the file."""
    head = text(path).splitlines()[0]
    return head, LINK.findall(head)


def blocks(path):
    """(info string, body lines) for every fenced block, in order."""
    out, info, body = [], None, []
    for line in text(path).splitlines():
        if info is None:
            if line.startswith("```"):
                info, body = line[3:].strip(), []
        elif line.startswith("```"):
            out.append((info, body))
            info = None
        else:
            body.append(line)
    assert info is None, f"{rel(path)} has an unclosed code block"
    return out


def commands(path):
    """Shell lines of the bash blocks, without comments and blanks."""
    return {
        line.strip()
        for info, body in blocks(path)
        if info in ("bash", "sh")
        for line in body
        if line.strip() and not line.strip().startswith("#")
    }


def test_all_files_exist():
    missing = [rel(p) for t in FAMILIES for p in files(t) if not p.is_file()]
    assert not missing, f"missing documentation files: {', '.join(missing)}"


def test_first_line_is_a_switcher():
    for template in FAMILIES:
        for path in files(template):
            head, targets = switcher(path)
            assert len(targets) == len(LANGS) - 1, (
                f"{rel(path)} does not start with a language switcher, first line: {head!r}"
            )


def test_switchers_link_the_other_languages():
    for template in FAMILIES:
        for path in files(template):
            head, targets = switcher(path)
            others = {p.name for p in files(template) if p != path}
            assert set(targets) == others, (
                f"{rel(path)} switcher links {sorted(targets)}, expected {sorted(others)}: {head!r}"
            )


def test_switcher_targets_exist():
    for template in FAMILIES:
        for path in files(template):
            for target in switcher(path)[1]:
                assert (path.parent / target).is_file(), (
                    f"{rel(path)} links {target}, which does not exist"
                )


def test_translations_keep_the_code_blocks():
    for template in FAMILIES:
        original = files(template)[0]
        expected = len(blocks(original))
        for path in files(template)[1:]:
            got = len(blocks(path))
            assert got == expected, (
                f"{rel(path)} has {got} fenced code blocks, {rel(original)} has {expected}"
            )


def test_translations_keep_the_shell_commands():
    for template in FAMILIES:
        original = files(template)[0]
        expected = commands(original)
        for path in files(template)[1:]:
            got = commands(path)
            assert got == expected, (
                f"{rel(path)} shell commands differ from {rel(original)}: "
                f"missing {sorted(expected - got)}, unexpected {sorted(got - expected)}"
            )


def main():
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_")]
    failed = 0
    for name, fn in tests:
        try:
            fn()
        except (AssertionError, OSError) as exc:
            failed += 1
            print(f"FAIL {name}: {exc}")
        else:
            print(f"ok   {name}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
