# Vault changelog

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
