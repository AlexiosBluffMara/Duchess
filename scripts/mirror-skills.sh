#!/usr/bin/env bash
# mirror-skills.sh — Keeps .github/skills/, .claude/skills/, .github/agents/, and .claude/agents/ in sync.
#
# Usage:
#   ./scripts/mirror-skills.sh              # Sync both directions (auto-detect newer)
#   ./scripts/mirror-skills.sh github       # Push .github/{skills,agents}/ → .claude/{skills,agents}/
#   ./scripts/mirror-skills.sh claude       # Push .claude/skills/ → .github/skills/
#   ./scripts/mirror-skills.sh check        # Dry-run: report drift without writing
#
# Duchess layout:
#   .github/skills/<name>/SKILL.md          ← Copilot / Duchess canonical skill format
#   .claude/skills/<name>.md                ← Claude Code flat skill format
#   .github/agents/<name>.agent.md          ← Copilot agent format
#   .claude/agents/<name>.md                ← Claude Code sub-agent format
#
# settings.json in .claude/ is rebuilt automatically on every sync.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GITHUB_SKILLS="$REPO_ROOT/.github/skills"
CLAUDE_SKILLS="$REPO_ROOT/.claude/skills"
GITHUB_AGENTS="$REPO_ROOT/.github/agents"
CLAUDE_AGENTS="$REPO_ROOT/.claude/agents"
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

# Rebuild .claude/settings.json from all files currently in .claude/skills/ and .claude/agents/.
rebuild_settings() {
  local skills_dir="$CLAUDE_SKILLS"
  local agents_dir="$CLAUDE_AGENTS"
  local settings="$CLAUDE_SETTINGS"

  python3 - "$skills_dir" "$agents_dir" "$settings" << 'PYEOF'
import sys, os, re, json

skills_dir, agents_dir, settings_path = sys.argv[1], sys.argv[2], sys.argv[3]

def frontmatter_desc(filepath):
    with open(filepath) as f:
        text = f.read()
    parts = text.split('---', 2)
    if len(parts) < 2:
        return ''
    for line in parts[1].strip().splitlines():
        m = re.match(r'^description:\s*(.*)', line)
        if m:
            val = m.group(1).strip()
            # strip surrounding quotes
            val = re.sub(r'^["\']|["\']$', '', val)
            return val
    return ''

skills = {}
if os.path.isdir(skills_dir):
    for fname in sorted(os.listdir(skills_dir)):
        if fname.endswith('.md'):
            fpath = os.path.join(skills_dir, fname)
            desc = frontmatter_desc(fpath) or f"{fname[:-3]} skill"
            skills[f"skills/{fname}"] = desc

agents = {}
if os.path.isdir(agents_dir):
    for fname in sorted(os.listdir(agents_dir)):
        if fname.endswith('.md'):
            fpath = os.path.join(agents_dir, fname)
            desc = frontmatter_desc(fpath) or f"{fname[:-3]} agent"
            agents[f"agents/{fname}"] = desc

with open(settings_path, 'w') as fh:
    json.dump({"skills": skills, "agents": agents}, fh, indent=2)
    fh.write('\n')
PYEOF

  local n_skills; n_skills=$(find "$skills_dir" -name "*.md" | wc -l | tr -d ' ')
  local n_agents; n_agents=$(find "$agents_dir" -name "*.md" 2>/dev/null | wc -l | tr -d ' ')
  info "Updated settings.json with ${n_skills} skills and ${n_agents} agents."
}

# ---------------------------------------------------------------------------
# Agent sync — .github/agents/<name>.agent.md ↔ .claude/agents/<name>.md
# ---------------------------------------------------------------------------

# Mirror a .github/agents/<name>.agent.md into .claude/agents/<name>.md
github_agent_to_claude() {
  local src="$1" dst="$2"
  local src_name; src_name=$(basename "$src" .agent.md)

  python3 - "$src" "$src_name" "$dst" << 'PYEOF'
import sys, re

src, name, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with open(src) as fh:
    text = fh.read()
parts = text.split('---', 2)
fm_raw = parts[1]
body = parts[2].lstrip('\n') if len(parts) > 2 else ''
fields = {}
for line in fm_raw.strip().splitlines():
    m = re.match(r'^(\w+):\s*(.*)', line)
    if m:
        key, val = m.group(1), m.group(2).strip()
        val = re.sub(r'^["\']|["\']$', '', val)
        fields[key] = val
out = ['---', f'name: {name}', f'description: "{fields.get("description", "")}"',
       f'tools: {fields.get("tools", "[]")}']
if 'agents' in fields:
    out.append(f'agents: {fields["agents"]}')
out += ['---', '', body]
with open(dst, 'w') as fh:
    fh.write('\n'.join(out))
PYEOF
  touch -r "$src" "$dst"
}

sync_agents() {
  local dry_run="${1:-false}"
  local changed=0
  mkdir -p "$CLAUDE_AGENTS"

  while IFS= read -r -d '' src; do
    local name; name=$(basename "$src" .agent.md)
    local dst="$CLAUDE_AGENTS/${name}.md"
    local needs_update=false
    [[ ! -f "$dst" ]] && needs_update=true
    [[ -f "$dst" ]] && [[ "$src" -nt "$dst" ]] && needs_update=true
    if [[ "$needs_update" == true ]]; then
      if [[ "$dry_run" == true ]]; then
        warn "DRIFT: .github/agents/${name}.agent.md → .claude/agents/${name}.md"
      else
        github_agent_to_claude "$src" "$dst"
        info "Mirrored agent: ${name}"
      fi
      ((changed++)) || true
    fi
  done < <(find "$GITHUB_AGENTS" -name "*.agent.md" -print0 | sort -z)

  echo "$changed"
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
    info "Pushing .github/agents/ → .claude/agents/"
    sync_agents false > /dev/null
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
    drift_a=$(sync_agents true)
    total=$((drift_g + drift_c + drift_a))
    if [[ "$total" -eq 0 ]]; then
      info "No drift detected. Skills and agents are in sync."
    else
      warn "${total} file(s) out of sync. Run without 'check' to fix."
      exit 1
    fi
    ;;
  auto|*)
    info "Auto-syncing skills and agents (bidirectional, newer file wins)..."
    changed_g=$(sync_github_to_claude false)
    changed_c=$(sync_claude_to_github false)
    changed_a=$(sync_agents false)
    total=$((changed_g + changed_c + changed_a))
    if [[ "$total" -gt 0 ]]; then
      rebuild_settings
      info "Sync complete. ${total} file(s) updated."
    else
      info "Already in sync."
    fi
    ;;
esac
