# Android Bridge (debug build)

A lightweight native Android companion for user-authorized Chrome/Gemini UI actions.

## Current behavior
- Starts **OFF** and requires the user to turn it ON.
- Provides an Emergency Stop that closes the local HTTP listener.
- Listens only on `127.0.0.1:8765`; it is not exposed directly to the LAN or Internet.
- Generates a 256-bit random pairing token and stores it in private app preferences.
- Reads HTTP request bodies as UTF-8 bytes and caps header/body size.
- Uses JSON parsing and constant-time pairing-token comparison.
- Opens only `https://gemini.google.com/` in the installed Chrome package.
- Restricts AccessibilityService read/click/type operations to Chrome windows that contain a visible “Gemini” indicator; typing requires the intended editable field to be focused.
- Reports a UI action as failed when it could not perform the action rather than automatically claiming success.

## Important limitations (read before use)
1. **There is no complete ChatGPT-plugin-to-APK connection in this repository yet.** The local server is reachable from the same phone only. A separately hosted, authenticated relay plus a real ChatGPT MCP/tool integration and an outbound APK connection are required for end-to-end control.
2. A visible “Gemini” text indicator is only a UI heuristic; it is **not cryptographic proof of the page origin**. AccessibilityService cannot reliably attest the Chrome URL from every screen. Do not use this as a security boundary for confidential data.
3. The implementation has not been tested on the user's actual phone. A successful CI build proves compilation/package creation only, not real-device behavior or full security.
4. This is a debug APK build. Do not treat it as a production-hardened or independently audited application.
5. Do not send passwords, cookies, Google authentication tokens, or recovery codes through bridge commands. Never bypass sign-in protections, CAPTCHA, rate limits, app rules, or anti-cheat controls.

## Local endpoint contract
All command requests use `POST` with a JSON body that includes the private pairing token:
- `/status`: report listener and accessibility state; explicitly reports that the plugin is not connected.
- `/open-url`: accepts only an HTTPS URL on `gemini.google.com`.
- `/read-screen`: reads visible text only when the active accessibility window passes the Chrome/Gemini UI heuristic.
- `/click-text`: clicks a visible exact text/description only when that same heuristic passes.
- `/type`: types only into an already focused editable field when the same heuristic passes.
- `GET /health`: local listener health only; it does not prove plugin or Gemini connectivity.

Each request must be sent only from the same phone to `127.0.0.1:8765`. This local-only HTTP endpoint must not be exposed publicly.

## Build and verification
GitHub Actions runs Android lint and assembles a debug APK with Gradle. Even when CI passes, the app still needs manual installation and real-device testing, followed by a separate end-to-end relay/plugin test.
