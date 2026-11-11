#!/usr/bin/env bash
# Every offline check of the feeder. The APK itself is built by tools/build-apk.sh.
#   tests/run-tests.sh
#   TVFEEDER_JSON_JAR=/path/json-20240303.jar tests/run-tests.sh
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 1
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
fails=0
check() { # name, command...
	name=$1; shift
	if "$@" >"$WORK/$name.log" 2>&1; then
		printf '[ ok ] %s\n' "$name"
	else
		printf '[FAIL] %s\n' "$name"
		tail -15 "$WORK/$name.log" | sed 's/^/       /'
		fails=$((fails + 1))
	fi
}
check app python3 tests/test_app.py
check generator python3 tests/test_fetch_top.py
check docs python3 tests/test_docs.py
if [ -n "${TVFEEDER_JSON_JAR:-}" ]; then
	check autonomous python3 tests/test_autonomous_feeder.py
else
	printf '[skip] autonomous: export TVFEEDER_JSON_JAR=/path/json-20240303.jar to run it\n'
fi
[ "$fails" -eq 0 ] && echo "all checks passed" || echo "$fails check(s) failed"
exit "$fails"
