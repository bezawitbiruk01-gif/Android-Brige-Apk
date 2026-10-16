# Android Bridge + Relay

This repository contains:
- A lightweight native Android companion APK.
- A remote Streamable HTTP MCP relay connecting the private ai-player plugin to an explicitly paired phone.
- GitHub Actions build and Android lint workflow.

## Architecture
ChatGPT / ai-player MCP -> HTTPS relay -> Android app outbound HTTPS long-poll -> Chrome/Gemini visible UI.

The phone never exposes its localhost port to the Internet. It initiates outbound requests to the relay. The MCP endpoint requires a bearer API key configured at connection time; keys are not checked into this repository. The device pairs via a one-time 6-digit code created by the authenticated MCP tool and typed into the app. Pair codes expire after 5 minutes; device tokens expire after 30 days.

## Important limitations
- The relay is a single-process in-memory queue. Pending commands and pairing codes are lost if the service restarts; re-pairing may be needed for commands after a restart.
- The APK still needs real-device installation and end-to-end testing.
- Visible “Gemini” UI text is only a heuristic, not cryptographic proof of the current page origin.
- Opening Chrome from a background service may be restricted by Android. The phone may require the user to bring the app/Chrome to the foreground.
- This is a prototype, not a security-audited production system. Do not send passwords, cookies, auth tokens or recovery codes through it. It must not bypass login protections, CAPTCHA, rate limits, or app rules.
- Keep Bridge OFF when not in use and use the Emergency Stop button if anything behaves unexpectedly.

## Environment variables
- `MCP_API_KEY`: random secret of at least 32 characters; used as Bearer auth for MCP requests.
- `DEVICE_SIGNING_SECRET`: independent random secret of at least 32 characters used to sign device tokens.
- Render provides `PORT`.

## Run locally
`npm install`
`MCP_API_KEY=... DEVICE_SIGNING_SECRET=... npm start`
