; grader-setup.iss — Bo cai Inno Setup cho "Grader" (cho may TRONG).
; Bien dich: chay build-installer.cmd (no tu cai Inno Setup neu thieu) -> tao installer\Output\Grader-Setup.exe
;
; Bo cai se: copy app + cap quyen ghi cho User + tao shortcut + (tuy chon) cai cac
; thanh phan nen (Docker/Node/Java/Python/Ollama) bang winget qua setup-prereqs.ps1.

#define AppName "Grader"
#define AppVersion "1.0.0"
#define Publisher "FPT Capstone"

[Setup]
AppId={{8C2F1A6E-7B43-4E0D-9A11-0B7C5E9D1A20}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#Publisher}
DefaultDirName={sd}\Grader
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
Compression=lzma
SolidCompression=yes
WizardStyle=modern
SourceDir=..
OutputDir=installer\Output
OutputBaseFilename=Grader-Setup
UninstallDisplayName={#AppName}

[Languages]
Name: "vi"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Tao bieu tuong ngoai man hinh (Desktop)"; GroupDescription: "Tuy chon:"
Name: "installprereqs"; Description: "Cai cac thanh phan can thiet (Docker, Node, Java, Python, Ollama) + tai model AI"; GroupDescription: "May trong (chua co Docker/Node/Java/Python/Ollama):"

[Files]
; CA cay Grader_App (gom backend + frontend + feedback-bot + scripts), LOAI BO cac thu muc tao sinh.
Source: "*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion; Excludes: "*.log,secret.properties,.env,.git\*,*\.git\*,*\node_modules\*,*\.next\*,*\target\*,*\.idea\*,*\__pycache__\*,*\.venv\*,submissions\*,*\submissions\*,*\chroma_db\*,installer\Output\*"

[Dirs]
; Cho User thuong duoc GHI vao thu muc cai (launcher tao .venv/node_modules/submissions luc chay)
Name: "{app}"; Permissions: users-modify

[Icons]
Name: "{group}\Khoi dong Grader"; Filename: "{app}\GraderLauncher.exe"; WorkingDir: "{app}"
Name: "{group}\Cai lai thanh phan nen"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\installer\setup-prereqs.ps1"" -AppDir ""{app}"""; WorkingDir: "{app}"
Name: "{group}\Go cai dat Grader"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Grader"; Filename: "{app}\GraderLauncher.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
; Cai cac thanh phan nen + tai model (chi khi nguoi dung chon). Chay duoi quyen admin cua Setup.
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\installer\setup-prereqs.ps1"" -AppDir ""{app}"""; StatusMsg: "Dang cai Docker/Node/Java/Python/Ollama + tai model (co the rat lau)..."; Flags: waituntilterminated; Tasks: installprereqs
; Tuy chon mo app ngay sau khi cai
Filename: "{app}\GraderLauncher.exe"; Description: "Khoi chay Grader ngay bay gio"; WorkingDir: "{app}"; Flags: postinstall nowait skipifsilent unchecked

[UninstallDelete]
; Don cac thu muc tao sinh khi go cai
Type: filesandordirs; Name: "{app}\feedback-bot\.venv"
Type: filesandordirs; Name: "{app}\feedback-bot\data\chroma_db"
Type: filesandordirs; Name: "{app}\frontend\node_modules"
Type: filesandordirs; Name: "{app}\frontend\.next"
Type: filesandordirs; Name: "{app}\grader\target"
