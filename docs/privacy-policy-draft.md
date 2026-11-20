# pFiles privacy policy — draft

Reviewed 2026-11-16. Developer: vladgba. Contact: vladgba@gmail.com. Confirm these details before publication.

## Data used by the app

The app reads and modifies selected files and folders. Exports, shared files and APK installation requests are delegated to Android or chosen apps.

## Network and other services

The main source manifest does not request Internet access. Android, cloud document providers, carriers, shared-to apps and account sync services may still handle information you choose to pass to them under their own policies.

## Storage, retention and deletion

Clear app data or uninstall to remove app-private data. This does not remove exported files, system contacts or messages, synchronized copies, cloud backups or provider accounts. Delete these through the relevant file location, system store or provider. Exact app-specific retention, backup exclusions and third-party deletion instructions require release review.

## Permissions

- `android.permission.MANAGE_EXTERNAL_STORAGE`
- `android.permission.READ_EXTERNAL_STORAGE`
- `android.permission.REQUEST_INSTALL_PACKAGES`
- `android.permission.WRITE_EXTERNAL_STORAGE`

Permissions support the features described above. Grant optional permissions only when needed; access can be managed in Android settings. The final release manifest and any dependency permissions must also be checked.

## Third-party software and children

SDK and runtime data practices, advertising status, intended audience and child-related obligations need confirmation. This draft makes no unsupported zero-collection, zero-tracking or child-safety claim.

## Changes

Update this policy when the released app’s data practices change.
