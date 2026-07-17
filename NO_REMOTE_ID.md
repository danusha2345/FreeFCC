# Why FreeFCC Does Not Include Remote ID Disable

This is the official position on why Remote ID disabling is not and will
never be a feature in FreeFCC. If anyone asks why, point them here.

## The Short Version

FCC unlock, 4G activation, and LED control are in a gray area. They are
legal in some regions and you can get authorization to use them. Remote ID
disabling is different. It is explicitly illegal in the US (FAA), EU
(EASA), UK (CAA), and most other regulated airspace. This is not a gray
area. It is actual law.

## The Real-World Danger

Remote ID is how aircraft see your drone. When a plane or helicopter is
flying near your area, their detection systems pick up the Remote ID
broadcast and can see exactly where your drone is in real time. This lets
pilots avoid your drone.

If you disable Remote ID, there is no way for a plane to know you are
there. The risk of a mid-air collision goes way up. If a small drone hits
a helicopter rotor or gets sucked into a plane engine, people can die.
Remote ID is the system that prevents that from happening.

Disabling it does not just hide you from law enforcement. It hides you
from the aircraft that need to see you to avoid hitting you.

## Why Other Features Are Different

- **FCC unlock** switches the radio to a power level that is legal in
  FCC-authorized regions. If you have authorization or live in an
  FCC region, this is fine.
- **4G activation** enables hardware the user already owns.
- **LED control** is just lights. Completely harmless.

Note: FreeFCC does NOT raise the altitude ceiling. The 120m CE limit is
enforced by the DJI Fly app via a C0 class flag, not by drone firmware —
no DUML command can lift it. See the README for the full explanation.

All of these are arguable as legitimate features for people in the right
regions or with the right authorization. Remote ID disable has no
legitimate use that is not illegal.

## The Bottom Line

FreeFCC will never include Remote ID disabling. The risk to human safety
and the legal exposure is not worth it. If you need to fly without Remote
ID, talk to your local aviation authority about getting a waiver or
exemption for your specific use case.