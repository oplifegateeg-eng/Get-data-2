WhatsApp Groups Exporter (Android) - reads the backup file of WhatsApp / WhatsApp Business on the phone
======================================================================================================

WHAT IT DOES
  You choose WhatsApp or WhatsApp Business. The app opens the end-to-end encrypted backup file that WhatsApp makes
  on the phone (msgstore.db.crypt15) with your own 64-digit key, and creates an Excel file in Downloads with:
    Summary, Groups, My Admin Groups, Members, Contacts, Issues
  - every group in the backup, creation date, last activity date, messages in the backup
  - members, admins, whether you are admin
  - each member's last message in each group (from the messages stored in the backup)
  - names and "In My Contacts?" from the phone's address book (optional permission)
  - which other groups each member is in
  The app has NO internet permission. The key and the messages never leave the phone, and the temporary decrypted
  copy is deleted when the export ends.

NOT INCLUDED
  - Group invite links: WhatsApp does not store them in the backup.
  - Names of people who are not in your phone contacts (WhatsApp does not keep them in the backup).
  - Anything newer than the backup: make a fresh backup right before you export.

UPDATE YOUR GITHUB PROJECT (the repository you already have)
  1. Unzip this file on your PC. It contains a folder named WaGroupsExporterAndroid (same name as before).
  2. In your repository click "Add file" > "Upload files". Drag the folder WaGroupsExporterAndroid into the page
     (the whole folder, like the first time) and click "Commit changes". Files with the same path are replaced.
  3. Open the "Actions" tab. A new "Build APK" run starts by itself (5-10 minutes).
  4. When it has a green tick, open "Releases" on the repository main page, open the newest release and download
     app-debug.apk. Install it on the phone (allow "Install unknown apps" for your browser if asked).
  5. Optional: delete app/src/main/assets/exporter.js from the repository; it belonged to the older WhatsApp Web
     version and is not used any more.

USE ON THE PHONE
  1. Open the app and choose WhatsApp or WhatsApp Business.
  2. Make the encrypted backup (the app shows the steps): WhatsApp > Settings > Chats > Chat backup >
     End-to-end encrypted backup > Turn on > "Use 64-digit encryption key instead". Copy the 64-digit key, then tap BACK UP.
  3. In the app tap "Choose msgstore.db.crypt15" and pick the file:
        Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15
        (WhatsApp Business: Android/media/com.whatsapp.w4b/WhatsApp Business/Databases/msgstore.db.crypt15)
  4. Paste the 64-digit key, leave "Use my phone contacts" ticked, tap "Create the Excel file".
  5. Wait (decrypting and reading a big backup can take several minutes; keep the screen on and the phone charging).
     When it says Finished, open the Excel file from Downloads.

IF SOMETHING FAILS
  The status line explains the problem. "Could not decrypt" means the key, the file type (crypt14/crypt12 cannot be
  used) or the backup date does not fit; the message shows the first bytes of the file. The Issues sheet of the Excel
  file lists every group that could not be read completely and any database notes. Send me the text you see.
