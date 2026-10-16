# Android Bridge + Relay

This repository contains:
- A native Android companion APK.
- A remote Streamable HTTP MCP relay for the private ai-player plugin.
- OAuth 2.1 authorization-code + PKCE login for ChatGPT-to-relay authentication.
- One-time phone pairing and an outbound HTTPS command-polling connection.
- GitHub Actions lint, relay syntax validation, and APK build.

## Architecture
ChatGPT / ai-player plugin --OAuth 2.1 + PKCE--> HTTPS relay <--outbound HTTPS long-poll-- Android Bridge APK -> Chrome/Gemini visible UI.

The phone never exposes its localhost port to the Internet. The phone initiates requests to the relay. The MCP endpoint verifies short-lived signed access tokens issued by the relay OAuth flow. ChatGPT's connection does not use a custom API key.

## Required Render environment variables
Configure both of these in the Render dashboard before OAuth pairing can work:
- `OWNER_PIN`: private owner-approved sign-in passphrase, at least 16 characters. The user enters this only on the relay's first-party OAuth authorization page, not in ChatGPT messages.
- `DEVICE_SIGNING_SECRET`: random secret of at least 32 characters. Use Render's secret-value generator if available. Never commit this value to GitHub or paste it into chat.

Render supplies `PORT`. Optional `PUBLIC_ORIGIN` defaults to `https://biruk-ai-player-relay.onrender.com`.

## Phone setup
1. Open the Android Bridge app.
2. Keep the bridge OFF until the relay has been configured and the plugin tools are available.
3. Open Android Bridge Accessibility settings only when you are ready to enable visible Chrome/Gemini interactions.
4. The app's local server at `127.0.0.1:8765` is phone-local only; ChatGPT cannot reach it directly.
5. Generate a one-time pairing code through the authenticated `create_pairing_code` MCP tool, enter it in the app, then turn the bridge ON.

## Supported MCP tools
- `create_pairing_code`: creates a one-time code lasting 5 minutes.
- `bridge_status`: checks whether the phone has recently polled the relay.
- `open_gemini`: asks the phone to launch the official Gemini URL in Chrome (accepted launch is not proof that the page loaded).
- `read_gemini_screen`: reads visible text only when Chrome plus a Gemini UI indicator are found.
- `click_gemini_text`: clicks an exact visible accessibility label on a qualifying screen.
- `type_gemini_text`: types into an already focused editable field; does not submit the prompt automatically.
- `emergency_stop`: asks an online paired phone to stop; the physical button is required if the phone or relay is offline.

## Security and limitations
- ChatGPT relay authentication uses OAuth 2.1 authorization-code with PKCE; all relay commands require a valid signed access token.
- Phone pairing uses a short-lived one-time code; device tokens are signed and expire after 30 days.
- The relay uses in-memory queues and authorization codes. Service restarts may interrupt commands or require pairing again.
- The visible word “Gemini” is only a UI heuristic, not cryptographic proof of website origin.
- Launching Chrome from a background service may be restricted by Android. The phone may require the user to bring the app or Chrome to the foreground.
- This is an early prototype, not an independently security-audited product. Do not send passwords, cookies, recovery codes, or Google login tokens through it. It must not bypass login, CAPTCHA, rate limits, app rules, or anti-cheat protections.
- End-to-end operation still requires configuration in Render, the plugin's OAuth connection, pairing, installation of the latest APK, and real-device testing.

## Build and verification
GitHub Actions runs `node --check relay/server.js`, relay smoke tests, Android lint, and `:app:assembleDebug`. A passing CI build is not proof of real-device behavior.
