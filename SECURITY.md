# Security Policy

## Reporting a Vulnerability

**Do NOT open public GitHub Issues for security vulnerabilities.**

If you discover a security vulnerability, please email us privately at [your-email] instead of using the issue tracker.

When reporting, include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

We will acknowledge your report within 48 hours and work on a fix.

## Security Best Practices for Contributors

- **Never commit** `secret.properties`, `.env`, API keys, or credentials
- **Check** `.gitignore` before pushing (should exclude `secret.properties` and other config with real keys)
- Backend uses HTTP/1.1 — don't downgrade protocol negotiation
- Validate all user input (exam uploads, skill codes)
- Keep dependencies updated: `mvnw.cmd dependency:check`

## Supported Versions

| Version | Supported          |
|---------|-------------------|
| main    | ✅ Active support  |
| Other   | ❌ No support      |
