#!/usr/bin/env bash
# Поднимает версию приложения и синхронизирует versionCode.
# Использование: ./tools/bump-version.sh [patch|minor|major]
set -euo pipefail
cd "$(dirname "$0")/.."

PART="${1:-patch}"
FILE="version.properties"

if [ ! -f "$FILE" ]; then
  echo "Не найден $FILE" >&2
  exit 1
fi

NAME="$(sed -n 's/^versionName=//p' "$FILE" | tail -n 1)"
CODE="$(sed -n 's/^versionCode=//p' "$FILE" | tail -n 1)"
NAME="${NAME:-1.0}"
CODE="${CODE:-1}"

IFS='.' read -r MAJOR MINOR PATCH <<< "$NAME"
MAJOR="${MAJOR:-1}"
MINOR="${MINOR:-0}"
PATCH="${PATCH:-0}"

case "$PART" in
  major)
    MAJOR=$((MAJOR + 1))
    MINOR=0
    PATCH=0
    ;;
  minor)
    MINOR=$((MINOR + 1))
    PATCH=0
    ;;
  patch)
    PATCH=$((PATCH + 1))
    ;;
  *)
    echo "Использование: $0 [patch|minor|major]" >&2
    exit 1
    ;;
esac

NEW_NAME="${MAJOR}.${MINOR}"
if [ "$PATCH" -gt 0 ]; then
  NEW_NAME="${NEW_NAME}.${PATCH}"
fi
NEW_CODE=$((CODE + 1))

cat > "$FILE" <<EOF
versionCode=$NEW_CODE
versionName=$NEW_NAME
EOF

echo "versionCode: $CODE -> $NEW_CODE"
echo "versionName: $NAME -> $NEW_NAME"
