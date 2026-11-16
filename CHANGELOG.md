# Changelog

All notable changes to Tether. Versions follow [Semantic Versioning](https://semver.org).

## [0.3.0] - 2026-11-16

- **One phone, several computers:** pair the phone with as many computers as you like; each keeps its own link.
- **Away from Wi-Fi:** a small relay (`tether-relay`, on a server you own) joins phone and computer over the internet when they aren't on the same Wi-Fi. Messages stay encrypted end to end; the relay only passes bytes along.
- **Tray icon** on the computer: click to show Tether, menu to quit, and the link status as its tooltip.
- **Phone updates itself:** Check for updates downloads the newest Tether and installs it.
- **Fixed:** the phone's screen didn't refresh after pairing.

## [0.2.0] - 2026-11-15

- **A real desktop app:** the phone and its sections in a sidebar; Messages shows your conversations beside the open one, with bubbles, and Enter sends. A narrow window goes back to tabs.
- **Works by itself in the background:** closing the window keeps Tether connected (notifications keep coming); it starts with the computer, without a window; opening Tether again just shows the window. "Quit Tether" in the sidebar ends it.
- Drop files anywhere on the window to send them to the phone; Ctrl+1…4 switch sections.
- **Phone:** asks once to run without a battery limit, so the link comes back on its own (on Xiaomi phones also turn on Autostart).
- **New icon**, drawn like every other Quezka app.

## [0.1.0] - 2026-11-14

- First version: your Android phone on your computer, over the home network, end-to-end encrypted.
- **Notifications** from the phone show on the computer; reply from there when the app allows it, or dismiss them.
- **Text messages:** read your latest conversations and send new messages from the computer; new messages pop up.
- **Calls:** see who's calling and answer or reject from the computer (the talking stays on the phone).
- **Files** both ways (share with Tether on the phone; *Send a file* on the computer) and the **clipboard** both ways.
- Pair once with an 8-character code shown on the computer; after that they find each other by themselves.
