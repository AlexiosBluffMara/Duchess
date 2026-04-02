# Jordan: Test package for Duchess cloud infrastructure.
# Run with: cd cloud && poetry run pytest tests/ -v
#
# Test categories:
#   test_duchess_stack.py   — CDK assertion tests (infra correctness)
#   test_handler.py         — Lambda handler unit tests (logic correctness)
#   test_integration.py     — End-to-end flow tests (pipeline correctness)
#
# Cost of running tests: $0.00 (all AWS calls are mocked via moto).
# Cost of NOT running tests: potentially unbounded.
