# Vault changelog

## v0.6.15

- New settings: the username or email filled in for new logins

## v0.6.14

- New setting: hide a shown password again after 5, 15 or 30 seconds, or never

## v0.6.13

- New settings: how long copied passwords stay on the clipboard, and a switch to block screenshots and screen recording
- Text size and accent colour chosen in Control Room now apply here
- New Rotate with the phone switch in Settings

## v0.6.12

- Loading screens now say what they are doing instead of showing a bare spinner
- Wording tidied so no sentence needs a dash

## v0.6.11

- New Codes tab: Vault is an authenticator now. Every two-factor code in one list, refreshing by themselves with a countdown; tap one to copy it. Works with any website that offers an authenticator app
- Add a code by scanning its QR code with the camera, by picking a screenshot of the QR, or by typing the setup key or pasting an otpauth:// link; save it as a new login or add it to one you already have
- Imports Google Authenticator exports (the Transfer accounts QR), so all your codes move over in one scan

## v0.6.10

- Import guides tell the truth: Firefox and Edge for Android can't export passwords, so their guides walk you through exporting on a computer instead; Samsung only exports an encrypted .spass file, and the guide says so
- Every browser is listed in Import and export, not just the ones on this phone
- Long guides scroll instead of being cut off
- Titles shrink to fit instead of being cut off

## v0.6.9

- Leaked-password check on the Health tab: finds passwords that appear in known data breaches (Have I Been Pwned). Only the first 5 characters of a scrambled copy of each password leave your phone, and only when you tap Check
- Fixed a crash when moving around with arrow keys or Tab while a screen was closing

## v0.6.8

- Lighter on the battery: in the Super look the drifting sparkles settle after a few seconds without a touch (the next tap wakes them), so a screen you're reading no longer redraws 25 times a second

## v0.6.7

- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.6.6

- Importing from Firefox, Chrome, Google and other browsers is easy to find: an Import button on the Passwords screen, on the empty vault, and in the menu. Tap a browser for a step-by-step guide that opens it, tells you exactly what to tap, then picks up the file
- Other Wonderdesk apps can keep their passwords in Vault: the Wayfinder Safe folder asks Vault for its password (unlocked with your fingerprint or master password) and can save it there when you create it

## v0.6.5

- Polish and fixes

## v0.6.4

- Fixes from a full round of stress testing on every screen

## v0.6.3

- Tap the tab you're already on to glide back to the top of its list
- Nothing required stays hidden: if Android is blocking something the app needs, a slim banner says what and takes you straight to the switch, and Settings › Permissions and access lists everything with its state

## v0.6.2

- Logins glide into place when you add or remove them
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.6.1

- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.6.0

- Polish and stability fixes from the suite-wide test pass
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.5.0

- Unlock with your fingerprint: after unlocking with your master password, Vault offers to turn it on, and Settings links to fingerprint setup
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.4.1

- Stronger strength meter: a password only counts as Strong when it mixes capitals, lowercase, numbers and symbols and has at least 12 characters, and Vault tells you what's missing
- Generated passphrases always include a capital, a number and a symbol
- New Browser and Google passwords: step-by-step export from Chrome, Firefox, Google Password Manager, Samsung Internet, Edge and Brave, then import the file. Works with Bitwarden, 1Password and LastPass exports too
- Duplicates merge automatically (changed passwords keep the old one in history), and Vault offers to delete the plain-text export afterwards
- Export Vault's passwords as a CSV that Chrome, Google and Firefox can import
- Two-factor codes only tick while you're looking at them
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.4.0

- Fingerprint unlock and autofill for Firefox and other apps
- Password and passphrase generator, two-factor codes, password health
- Recovery code and password history; the file stays compatible with Vault on Linux
