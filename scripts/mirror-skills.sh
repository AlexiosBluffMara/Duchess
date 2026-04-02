#!/usr/bin/env bash
# mirror-skills.sh — Keeps .github/skills/ and .claude/skills/ in sync.
#
# Usage:
#   ./scripts/mirror-skills.sh              # Sync both directions (auto-detect newer)
#   ./scripts/mirror-skills.sh github       # Push .github/skills/ → .claude/skills/
#   ./scripts/mirror-skills.sh claude       # Push .claude/skills/  → .github/skills/
#   ./scripts/mirror-skills.sh check        # Dry-run: report drift without writing
#
# Duchess skill layout:
#   .github/skills/<name>/SKILL.md          ← Copilot / Duchess canonical format
#   .claude/skills/<name>.md                ← Claude Code flat format
#
# The two formats contain the same prose but different frontmatter.
# Mirroring copies content WITHOUT the frontmatter header block.
# The claude variant always has a minimal "# <Name>" heading as its first line.
#
# settings.json in .claude/ is updated automatically when new skills appear.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GITHUB_SKILLS="$REPO_ROOT/.github/skills"
CLAUDE_SKILLS="$REPO_ROOT/.claude/skills"
CLAUDE_SETTINGS="$REPO_ROOT/.claude/settings.json"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[mirror]${NC} $*" >&2; }
warn()  { echo -e "${YELLOW}[mirror]${NC} $*" >&2; }
error() { echo -e "${RED}[mirror]${NC} $*" >&2; }

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Strip YAML frontmatter (--- ... ---) from a file, print the rest.
# Also drops leading blank lines after the frontmatter block.
strip_frontmatter() {
  local file="$1"
  awk '
    /^---/ { block++; next }
    block < 2 { next }
    /^[[:space:]]*$/ && !content { next }
    { content=1; print }
  ' "$file"
}

# Extract a frontmatter field value (e.g. name, description).
frontmatter_field() {
  local file="$1" field="$2"
  awk -v f="$field" '
    /^---/{block++; next}
    block==1 && $0 ~ "^"f":" {
      sub("^"f":[ \t]*", ""); print; exit
    }
  ' "$file"
}

# Convert a .github SKILL.md to a .claude flat skill file.
github_to_claude() {
  local src="$1"   # e.g. .github/skills/android-development/SKILL.md
  local dst="$2"   # e.g. .claude/skills/android-development.md

  local name; name=$(frontmatter_field "$src" "name")
  local desc; desc=$(frontmatter_field "$src" "description")
  local body; body=$(strip_frontmatter "$src")

  mkdir -p "$(dirname "$dst")"
  {
    echo "---"
    echo "description: ${desc}"
    echo "---"
    echo ""
    echo "# ${name}"
    echo ""
    echo "${body}"
  } > "$dst"
  touch -r "$src" "$dst"  # preserve source mtime to prevent false drift on next check
}

# Convert a .claude flat skill file to a .github SKILL.md.
claude_to_github() {
  local src="$1"   # e.g. .claude/skills/android-development.md
  local dst="$2"   # e.g. .github/skills/android-development/SKILL.md

  local desc; desc=$(frontmatter_field "$src" "description")
  local body; body=$(strip_frontmatter "$src")
  # Derive name from first heading in body
  local name; name=$(echo "$body" | awk '/^# /{sub(/^# /,""); print; exit}')
  [[ -z "$name" ]] && name=$(basename "$src" .md)

  mkdir -p "$(dirname "$dst")"
  {
    echo "---"
    echo "name: ${name}"
    echo "description: \"${desc}\""
    echo "---"
    echo ""
    echo "${body}"
  } > "$dst"
  touch -r "$src" "$dst"  # preserve source mtime to prevent false drift on next check
}

# Rebuild .claude/settings.json from all files currently in .claude/skills/.
rebuild_settings() {
  local skills_dir="$CLAUDE_SKILLS"
  local settings="$CLAUDE_SETTINGS"

  # Gather existing non-Duchess entries (DAT SDK built-ins) that reference
  # files that still exist — preserve them.
  local tmp; tmp=$(mktemp)

  echo '{' > "$tmp"
  echo '  "skills": {' >> "$tmp"

  local first=true
  while IFS= read -r -d '' skill_file; do
    local rel_path; rel_path="skills/$(basename "$skill_file")"
    local desc; desc=$(frontmatter_field "$skill_file" "description")
    [[ -z "$desc" ]] && desc="$(basename "$skill_file" .md) skill"

    if [[ "$first" == true ]]; then
      first=false
    else
      echo ',' >> "$tmp"
    fi
    printf '    "%s": "%s"' "$rel_path" "$desc" >> "$tmp"
  done < <(find "$skills_dir" -name "*.md" -print0 | sort -z)

  echo '' >> "$tmp"
  echo '  }' >> "$tmp"
  echo '}' >> "$tmp"

  mv "$tmp" "$settings"
  info "Updated settings.json with $(find "$skills_dir" -name "*.md" | wc -l | tr -d ' ') skills."
}

# ---------------------------------------------------------------------------
# Sync logic
# ---------------------------------------------------------------------------

sync_github_to_claude() {
  local dry_run="${1:-false}"
  local changed=0

  while IFS= read -r -d '' skill_md; do
    local skill_name; skill_name=$(basename "$(dirname "$skill_md")")
    local claude_dst="$CLAUDE_SKILLS/${skill_name}.md"

    local needs_update=false
    if [[ ! -f "$claude_dst" ]]; then
      needs_update=true
    elif [[ "$skill_md" -nt "$claude_dst" ]]; then
      needs_update=true
    fi

    if [[ "$needs_update" == true ]]; then
      if [[ "$dry_run" == true ]]; then
        warn "DRIFT: .github/skills/${skill_name}/SKILL.md → .claude/skills/${skill_name}.md"
      else
        github_to_claude "$skill_md" "$claude_dst"
        info "Mirrored: .github/skills/${skill_name}/SKILL.md → .claude/skills/${skill_name}.md"
      fi
      ((changed++)) || true
    fi
  done < <(find "$GITHUB_SKILLS" -name "SKILL.md" -print0 | sort -z)

  echo "$changed"
}

sync_claude_to_github() {
  local dry_run="${1:-false}"
  local changed=0

  while IFS= read -r -d '' skill_md; do
    # Skip DAT SDK built-ins that don't have a .github counterpart by design
    local skill_name; skill_name=$(basename "$skill_md" .md)
    local github_dst="$GITHUB_SKILLS/${skill_name}/SKILL.md"

    local needs_update=false
    if [[ ! -f "$github_dst" ]]; then
      needs_update=true
    elif [[ "$skill_md" -nt "$github_dst" ]]; then
      needs_update=true
    fi

    if [[ "$needs_update" == true ]]; then
      if [[ "$dry_run" == true ]]; then
        warn "DRIFT: .claude/skills/${skill_name}.md → .github/skills/${skill_name}/SKILL.md"
      else
        claude_to_github "$skill_md" "$github_dst"
        info "Mirrored: .claude/skills/${skill_name}.md → .github/skills/${skill_name}/SKILL.md"
      fi
      ((changed++)) || true
    fi
  done < <(find "$CLAUDE_SKILLS" -name "*.md" -print0 | sort -z)

  echo "$changed"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

MODE="${1:-auto}"

case "$MODE" in
  github)
    info "Pushing .github/skills/ → .claude/skills/"
    sync_github_to_claude false > /dev/null
    rebuild_settings
    ;;
  claude)
    info "Pushing .claude/skills/ → .github/skills/"
    sync_claude_to_github false > /dev/null
    ;;
  check)
    info "Checking for drift (dry-run)..."
    drift_g=$(sync_github_to_claude true)
    drift_c=$(sync_claude_to_github true)
    total=$((drift_g + drift_c))
    if [[ "$total" -eq 0 ]]; then
      info "No drift detected. Skills are in sync."
    else
      warn "${total} skill file(s) out of sync. Run without 'check' to fix."
      exit 1
    fi
    ;;
  auto|*)
    info "Auto-syncing skills (bidirectional, newer file wins)..."
    changed_g=$(sync_github_to_claude false)
    changed_c=$(sync_claude_to_github false)
    total=$((changed_g + changed_c))
    if [[ "$total" -gt 0 ]]; then
      rebuild_settings
      info "Sync complete. ${total} file(s) updated."
    else
      info "Already in sync."
    fi
    ;;
esac
