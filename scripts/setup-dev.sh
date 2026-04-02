#!/usr/bin/env bash
# scripts/setup-dev.sh
#
# One-time setup for a new Duchess contributor (or re-setup after a clean clone).
# Run once:  bash scripts/setup-dev.sh
#
# What it does:
#   1. Registers .githooks/ as the git hooks directory
#   2. Makes all hooks executable
#   3. Runs the skill mirror to ensure .claude/ and .github/skills/ are in sync
#   4. Checks for required tooling (git, bash 5+)

set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[setup]${NC} $*"; }
warn()  { echo -e "${YELLOW}[setup]${NC} $*"; }
error() { echo -e "${RED}[setup]${NC} $*" >&2; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo ""
echo "━━━ Duchess dev environment setup ━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ---------------------------------------------------------------------------
# 1. Check tooling
# ---------------------------------------------------------------------------
MISSING_TOOLS=()
for tool in git bash; do
  command -v "$tool" &>/dev/null || MISSING_TOOLS+=("$tool")
done

if [[ "${#MISSING_TOOLS[@]}" -gt 0 ]]; then
  error "Missing required tools: ${MISSING_TOOLS[*]}"
  exit 1
fi

BASH_VERSION_MAJOR="${BASH_VERSINFO[0]}"
if [[ "$BASH_VERSION_MAJOR" -lt 5 ]]; then
  warn "bash < 5 detected. Scripts are tested on bash 5+. Consider: brew install bash"
fi

info "Tooling: OK"

# ---------------------------------------------------------------------------
# 2. Register git hooks
# ---------------------------------------------------------------------------
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit .githooks/commit-msg .githooks/post-merge
info "Git hooks registered: .githooks/ (pre-commit, commit-msg, post-merge)"

# ---------------------------------------------------------------------------
# 3. Skill mirror sync
# ---------------------------------------------------------------------------
info "Syncing skills..."
bash "$REPO_ROOT/scripts/mirror-skills.sh" auto

# ---------------------------------------------------------------------------
# 4. Remind about GitHub token for Maven
# ---------------------------------------------------------------------------
if [[ ! -f "$REPO_ROOT/local.properties" ]] || ! grep -q "github_token" "$REPO_ROOT/local.properties" 2>/dev/null; then
  echo ""
  warn "local.properties missing github_token."
  warn "The Meta DAT SDK requires a GitHub PAT with 'read:packages' scope:"
  warn "  echo 'github_token=ghp_YOUR_TOKEN' >> local.properties"
  warn "local.properties is gitignored — safe to store the token there."
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
info "Setup complete. You're ready to contribute to Duchess."
echo ""
echo "  Spec workflow:   see specs/_template.spec.md"
echo "  Skill authoring: see .github/instructions on mirroring"
echo "  Build:           see .claude/commands/build.md"
echo ""
