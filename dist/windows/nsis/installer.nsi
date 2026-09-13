#
# NSIS installer script for the Weather Map.
#
# Adapted from ../vassal/dist/windows/nsis/installer.nsi (c) 2008-2021 by
# Joel Uckelman, used under the LGPL.
#
#  This library is free software; you can redistribute it and/or
#  modify it under the terms of the GNU Library General Public
#  License (LGPL) as published by the Free Software Foundation.
#
# Differences from the installer this was adapted from, deliberately:
#
# - No file associations. Weather Map reads GRIB2 it downloads itself and
#   utility must never steal them.
# - The uninstall registry KEY is "Weather-Map <version>"
#   (hyphenated), so an installer for an unrelated product cannot match it
#   when enumerating keys by prefix to offer removing old versions.
#

#
# General Configuration
#

Unicode true
CRCCheck on

; Note: VERSION, PKGVERSION, NUMVERSION, ARCH, BITS, and TMPDIR are defined from
; the command line in the Makefile. These are here as a reminder only.
;!define VERSION "1.0.18-abc1234"
;!define PKGVERSION "1.0.18"
;!define NUMVERSION "1.0.18"
;!define ARCH x86_64
;!define BITS 64
;!define TMPDIR "tmp"

!define INCDIR "${TMPDIR}/windows-${ARCH}-build"

!define APPEXE "Weather-Map.exe"

!define UNINST "Software\Microsoft\Windows\CurrentVersion\Uninstall"
; the uninstall key: prefixed with the product name — see header comment
!define VNAME "Weather-Map ${VERSION}"
!define UROOT "${UNINST}\${VNAME}"
; the 25-character key prefix our own version enumeration matches on
!define KEYPREFIX "Weather-Map "

Name "Weather Map"
; The installer file name carries only PKGVERSION and the architecture: the
; GitHub releases page truncates long names, cutting off the very suffix that
; tells a user which download is theirs. VERSION still names the install
; directory and the product version below.
OutFile "${TMPDIR}/Weather-Map-${PKGVERSION}-${ARCH}.exe"

!if ${BITS} == 64
  InstallDir "$PROGRAMFILES64\Weather-Map-${VERSION}"
!else
  InstallDir "$PROGRAMFILES\Weather-Map-${VERSION}"
!endif

!define ARCH_PRETTY_X86_32 "x86 32-bit"
!define ARCH_PRETTY_X86_64 "x86 64-bit"
!define ARCH_PRETTY_AARCH64 "ARM 64-bit"

!if ${ARCH} == "x86_64"
  !define ARCH_PRETTY "${ARCH_PRETTY_X86_64}"
!else if ${ARCH} == "x86_32"
  !define ARCH_PRETTY "${ARCH_PRETTY_X86_32}"
!else if ${ARCH} == "aarch64"
  !define ARCH_PRETTY "${ARCH_PRETTY_AARCH64}"
!else
  !error "ARCH ${ARCH} unknown"
!endif

VIProductVersion ${NUMVERSION}.0
VIAddVersionKey ProductName "Weather Map"
VIAddVersionKey CompanyName weathermap
VIAddVersionKey LegalCopyright "Weather Map contributors"
VIAddVersionKey FileDescription "Weather Map installer"
VIAddVersionKey FileVersion ${NUMVERSION}.0
VIAddVersionKey ProductVersion ${VERSION}
VIAddVersionKey InternalName weathermap

RequestExecutionLevel admin

# compression
SetCompress auto
SetCompressor /SOLID /FINAL lzma
SetDatablockOptimize on

# includes for various functions
!include "FileFunc.nsh"
!include "nsDialogs.nsh"
!include "WinMessages.nsh"
!include "WinVer.nsh"
!include "WordFunc.nsh"
!include "x64.nsh"

!addincludedir "dist/windows/nsis"

#
# Modern UI 2 setup
#
!include "MUI2.nsh"
!define MUI_ABORTWARNING

!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "${NSISDIR}\Contrib\Graphics\Header\orange.bmp"
!define MUI_HEADERIMAGE_UNBITMAP "${NSISDIR}\Contrib\Graphics\Header\orange-uninstall.bmp"

!define MUI_ICON "${NSISDIR}\Contrib\Graphics\Icons\orange-install.ico"
!define MUI_UNICON "${NSISDIR}\Contrib\Graphics\Icons\orange-uninstall.ico"

!define MUI_WELCOMEFINISHPAGE_BITMAP "${NSISDIR}\Contrib\Graphics\Wizard\orange.bmp"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP "${NSISDIR}\Contrib\Graphics\Wizard\orange-uninstall.bmp"

#
# Install Pages
#

; Welcome page
!define MUI_WELCOMEPAGE_TITLE_3LINES
!insertmacro MUI_PAGE_WELCOME

; Setup Type page
Page custom preSetupType leaveSetupType

; Uninstall Old Versions page
Page custom preUninstallOld leaveUninstallOld

; Select Install Directory page
!define MUI_PAGE_CUSTOMFUNCTION_PRE preDirectory
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE leaveDirectory
!define MUI_DIRECTORYPAGE_VERIFYONLEAVE
!insertmacro MUI_PAGE_DIRECTORY

; Shortcuts page
Page custom preShortcuts leaveShortcuts

; Start Menu page
Var StartMenuFolder
!define MUI_PAGE_CUSTOMFUNCTION_PRE preStartMenu
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE leaveStartMenu
!define MUI_STARTMENUPAGE_NODISABLE
!define MUI_STARTMENUPAGE_DEFAULTFOLDER "Weather Map"
!define MUI_STARTMENUPAGE_REGISTRY_ROOT HKLM
!define MUI_STARTMENUPAGE_REGISTRY_KEY "${UROOT}"
!define MUI_STARTMENUPAGE_REGISTRY_VALUENAME "StartMenuFolder"
!insertmacro MUI_PAGE_STARTMENU StartMenu $StartMenuFolder

; Confirm Install page
Page custom preConfirm leaveConfirm

; Install Files page
!insertmacro MUI_PAGE_INSTFILES

; Finish page
!define MUI_FINISHPAGE_NOAUTOCLOSE
!define MUI_FINISHPAGE_TITLE_3LINES
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION launchApp
!insertmacro MUI_PAGE_FINISH

#
# Uninstall Pages
#

; Welcome page
!insertmacro MUI_UNPAGE_WELCOME

; Confirm page
!insertmacro MUI_UNPAGE_CONFIRM

; Remove Files page
!insertmacro MUI_UNPAGE_INSTFILES

; Finish page
!define MUI_UNFINISHPAGE_NOAUTOCLOSE
!insertmacro MUI_UNPAGE_FINISH

; must be set after the pages, or header graphics fail to show up!
!insertmacro MUI_LANGUAGE "English"

#
# Macros
#

; skips a page in a Standard install
!macro SkipIfNotCustom
  ${If} $CustomSetup == 0
    Abort
  ${EndIf}
!macroend

!define SkipIfNotCustom "!insertmacro SkipIfNotCustom"


!macro WaitForAppToClose
  #
  # Detect running instances of the utility.
  # Based on http://nsis.sourceforge.net/Get_a_list_of_running_processes.
  #

check_processes:
  ; allocate a buffer
  System::Alloc 1024
  Pop $R9

  ; get the process array
  System::Call "Psapi::EnumProcesses(i R9, i 1024, *i .R1)i .R8"
  ${If} $R8 == 0
    System::Free $R9
    Goto cannot_check
  ${EndIf}

  IntOp $R2 $R1 / 4 ; Divide by sizeof(DWORD) to get number of processes
  StrCpy $R4 0      ; R4 is our counter variable

  ${Do}
    System::Call "*$R9(i .R5)" ; Get next PID
    ${If} $R5 == 0      ; skip PID 0
      Goto next_iteration
    ${ElseIf} $R5 < 0   ; done if PID < 0
      ${Break}
    ${EndIf}

    System::Call "Kernel32::OpenProcess(i 1040, i 0, i R5)i .R8"
    ${If} $R8 == 0
      Goto next_iteration
    ${EndIf}

    System::Alloc 1024
    Pop $R6

    System::Call "Psapi::EnumProcessModules(i R8, i R6, i 1024, *i .R1)i .R7"
    ${If} $R7 == 0
      System::Free $R6
      GoTo next_iteration
    ${EndIf}

    System::Alloc 256
    Pop $R7

    System::Call "*$R6(i .r6)" ; Get next module
    System::Free $R6
    System::Call "Psapi::GetModuleBaseName(i R8, i r6, t .R7, i 256)i .r6"

    ${If} $R6 == 0
      System::Free $R7
      System::Free $R9
      GoTo cannot_check
    ${EndIf}

    ${If} $R7 == "${APPEXE}"
      System::Free $R7
      System::Free $R9

      MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION "An instance of the Weather Map is currently running.$\n$\nPlease close it and press OK to continue, or Cancel to quit." IDCANCEL bail_out
      Sleep 500
      Goto check_processes
    ${EndIf}

    System::Free $R7

next_iteration:
    IntOp $R4 $R4 + 1 ; Add 1 to our counter
    IntOp $R9 $R9 + 4 ; Add sizeof(int) to our buffer address
  ${LoopWhile} $R4 < $R2

  System::Free $R9
  Return

cannot_check:
  MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION "Unable to determine whether an instance of the Weather Map is running.$\n$\nPlease close all instances of the Weather Map before proceeding." IDCANCEL bail_out
  Return

bail_out:
  Abort
!macroend

!define WaitForAppToClose "!insertmacro WaitForAppToClose"


; Find installed versions of the utility by enumerating uninstall registry
; keys whose names begin with our 25-character prefix. This never matches
; Old versions of this product, matched by the key prefix below.
!macro FindUtilityVersions
  StrCpy $R0 0
  ${Do}
    EnumRegKey $0 HKLM "${UNINST}" $R0
    StrCpy $R1 "$0" 25
    ${If} $R1 == "${KEYPREFIX}"
      ${WordFind} "$RemoveOtherVersions" "$\n" "E/$R0" $1
      IfErrors 0 +2
      StrCpy $RemoveOtherVersions "$RemoveOtherVersions$0$\n"
      ClearErrors
    ${EndIf}
    IntOp $R0 $R0 + 1
  ${LoopUntil} $0 == ""
!macroend

!define FindUtilityVersions "!insertmacro FindUtilityVersions"


#
# Setup Option Variables
#
Var CustomSetup
Var AddDesktopSC
Var AddStartMenuSC
Var RemoveOtherVersions


#
# Functions
#
Function un.onInit
  ${WaitForAppToClose}
FunctionEnd


Function .onInit
  ; save registers
  Push $0

  ; Check ARM first, RunningX64 is true for 64-bit ARM (wtf?)
  ${If} ${IsNativeARM64}
    StrCpy $0 "ARM 64-bit"
  ${ElseIf} ${RunningX64}
    StrCpy $0 "x86 64-bit"
  ${Else}
    StrCpy $0 "x86 32-bit"
  ${EndIf}

  ${If} "$0" != "${ARCH_PRETTY}"
    # wrong package for your architecture
    MessageBox MB_OK|MB_ICONEXCLAMATION "This installer requires ${ARCH_PRETTY} Windows.$\n$\nTo use the Weather Map ${VERSION} on $0 Windows, please install the $0 Windows package."
    Abort
  ${EndIf}

  ; restore registers
  Pop $0

  ${WaitForAppToClose}
FunctionEnd


Function preSetupType
  ; save registers
  Push $0

  !insertmacro MUI_HEADER_TEXT "Setup Type" "Choose setup options"

  nsDialogs::Create /NOUNLOAD 1018
  Pop $0

  ${NSD_CreateLabel} 0 0 100% 12u "Choose the type of setup you prefer, then click Next."
	Pop $0
  ${NSD_CreateRadioButton} 15u 23u 100% 12u "&Standard"
	Pop $0
  SendMessage $0 ${BM_SETCHECK} ${BST_CHECKED} 1   ; select Standard
  ${NSD_CreateLabel} 30u 37u 100% 12u "The Weather Map will be installed with the most common options."
	Pop $0
  ${NSD_CreateRadioButton} 15u 54u 100% 12u "&Custom"
	Pop $CustomSetup
  ${NSD_CreateLabel} 30u 68u 100% 24u "You may choose individual options to be installed. Recommended for experienced$\nusers."
	Pop $0

  nsDialogs::Show

  ; restore registers
  Pop $0
FunctionEnd


Function leaveSetupType
  ; read the install type from the Custom radio button
  ${NSD_GetState} $CustomSetup $CustomSetup
FunctionEnd


Var KeepListBox
Var RemoveListBox
Var KeepButton
Var RemoveButton

Function preUninstallOld
  StrCpy $RemoveOtherVersions ""

  ; find all versions of the utility, checking the 32- and 64-bit hives
  SetRegView 32
  ${FindUtilityVersions}

  ${If} ${BITS} == 64
    SetRegView 64
    ${FindUtilityVersions}
  ${EndIf}

  ; remove all versions in Standard setup, skip this page
  ${SkipIfNotCustom}

  ${If} $RemoveOtherVersions == ""
    ; no versions installed, skip this page
  ${OrIf} $RemoveOtherVersions == "${VNAME}$\n"
    ; only this version installed, remove it and skip this page
    Abort
  ${EndIf}

  !insertmacro MUI_HEADER_TEXT "Remove Old Versions" "Uninstalling previous versions of the Weather Map"

  nsDialogs::Create /NOUNLOAD 1018
  Pop $0

  ${NSD_CreateLabel} 0 0 100% 24u "The installer has found these other versions of the Weather Map installed on your computer. Please select the versions you would like to remove now."
  Pop $0

  ${NSD_CreateLabel} 0 32u 120u 12u "To Keep:"
  Pop $0

  ${NSD_CreateListBox} 0 44u 120u 90u ""
  Pop $KeepListBox

  ${NSD_CreateButton} 125u 74u 50u 14u "Remove >"
  Pop $RemoveButton
  ${NSD_OnClick} $RemoveButton removeClicked

  ${NSD_CreateButton} 125u 90u 50u 14u "< Keep"
  Pop $KeepButton
  ${NSD_OnClick} $KeepButton keepClicked

  ${NSD_CreateLabel} 180u 32u 120u 12u "To Remove:"
  Pop $0

  ${NSD_CreateListBox} 180u 44u 120u 90u ""
  Pop $RemoveListBox

  ; populate the keep list
  StrCpy $R1 "$RemoveOtherVersions"
  StrCpy $RemoveOtherVersions ""
  StrCpy $R0 0
  ${Do}
    IntOp $R0 $R0 + 1
    ${WordFind} "$R1" "$\n" "E+$R0" $1
    IfErrors 0 +3
    ClearErrors
    ${Break}

    ${If} $1 == "${VNAME}"
      ; automatically uninstall existing copies of this version
      StrCpy $RemoveOtherVersions "${VNAME}$\n"
    ${Else}
      ; add entries for versions which are not this one
      SendMessage $KeepListBox ${LB_ADDSTRING} 0 "STR:$1"
      Pop $0
    ${EndIf}
  ${Loop}

  ; ready the buttons
  SendMessage $KeepListBox ${LB_SETCURSEL} 0 0
  Call adjustButtons

  ${NSD_OnChange} $KeepListBox adjustButtons
  ${NSD_OnChange} $RemoveListBox adjustButtons

  nsDialogs::Show
FunctionEnd


Function moveSelection
  ; move selected item from box $R1 to box $R0
  Pop $R0
  Pop $R1
  SendMessage $R1 ${LB_GETCURSEL} 0 0 $0
  ${If} $0 == LB_ERR
    Return
  ${EndIf}
  System::Call "user32::SendMessage(i $R1,i ${LB_GETTEXT},i r0, t .r1)i .r2"
  SendMessage $R1 ${LB_DELETESTRING} $0 0
  SendMessage $R0 ${LB_ADDSTRING} 0 "STR:$1"
FunctionEnd


Function adjustButtons
  ; disable a button if its source listbox has no selection
  SendMessage $KeepListBox ${LB_GETCURSEL} 0 0 $0
  ${If} $0 == -1
    StrCpy $0 0
  ${Else}
    StrCpy $0 1
  ${EndIf}
  EnableWindow $RemoveButton $0

  SendMessage $RemoveListBox ${LB_GETCURSEL} 0 0 $0
  ${If} $0 == -1
    StrCpy $0 0
  ${Else}
    StrCpy $0 1
  ${EndIf}
  EnableWindow $KeepButton $0
FunctionEnd


Function removeClicked
  ; move a selected item from Keep to Remove
  Push $KeepListBox
  Push $RemoveListBox
  Call moveSelection
  Call adjustButtons
FunctionEnd


Function keepClicked
  ; move a selected item from Remove to Keep
  Push $RemoveListBox
  Push $KeepListBox
  Call moveSelection
  Call adjustButtons
FunctionEnd


Function leaveUninstallOld
  ; collect the old versions to be removed from the remove list box
  SendMessage $RemoveListBox ${LB_GETCOUNT} 0 0 $1
  ${For} $0 0 $1
    System::Call "user32::SendMessage(i $RemoveListBox,i ${LB_GETTEXT},i r0, t .r2)i .r4"
    StrCpy $RemoveOtherVersions "$RemoveOtherVersions$2$\n"
  ${Next}
FunctionEnd


Function preDirectory
  ${SkipIfNotCustom}
FunctionEnd


Function leaveDirectory
FunctionEnd


Function preShortcuts
  ; save registers
  Push $0

  ; set shortcuts defaults
  StrCpy $AddDesktopSC 1
  StrCpy $AddStartMenuSC 1

  ; present user with choices in a custom install
  ${SkipIfNotCustom}
  !insertmacro MUI_HEADER_TEXT "Set Up Shortcuts" "Create Program Icons"

  nsDialogs::Create /NOUNLOAD 1018
  Pop $0

  ${NSD_CreateLabel} 0 0 100% 12u "Create icons for the Weather Map:"
  Pop $0
  ${NSD_CreateCheckBox} 15u 20u 100% 12u "On my &Desktop"
  Pop $AddDesktopSC
  SendMessage $AddDesktopSC ${BM_SETCHECK} ${BST_CHECKED} 1
  ${NSD_CreateCheckBox} 15u 40u 100% 12u "In my &Start Menu Programs folder"
  Pop $AddStartMenuSC
  SendMessage $AddStartMenuSC ${BM_SETCHECK} ${BST_CHECKED} 1

  nsDialogs::Show

  ; restore registers
  Pop $0
FunctionEnd


Function leaveShortcuts
  ; read which shortcuts to create from the check boxes
  ${NSD_GetState} $AddDesktopSC $AddDesktopSC
  ${NSD_GetState} $AddStartMenuSC $AddStartMenuSC
FunctionEnd


Function preStartMenu
  ${SkipIfNotCustom}
  ; also skip if the user unselected this option
  ${If} $AddStartMenuSC == 0
    Abort
  ${EndIf}
FunctionEnd


Function leaveStartMenu
FunctionEnd


Function preConfirm
  ; save registers
  Push $0

  !insertmacro MUI_HEADER_TEXT "Ready to Install" "Please confirm that you are ready to install"

  nsDialogs::Create /NOUNLOAD 1018
  Pop $0

  ${NSD_CreateLabel} 0 0 100% 100% "The installer is ready to install the Weather Map on your computer.$\n$\n$\nClick $\"Install$\" to start the installation."
  Pop $0

  nsDialogs::Show

  ; restore registers
  Pop $0
FunctionEnd


Function leaveConfirm
FunctionEnd


Function launchApp
  ; Launch via explorer.exe because it is already running as the user,
  ; not as admin, which will launch us as user also.
  Exec '"$WINDIR\explorer.exe" "$INSTDIR\${APPEXE}"'
FunctionEnd


#
# Install Section
#
Section "-Application" Application
  SectionIn RO

  ; remove old versions of the utility, if requested
  ${If} $RemoveOtherVersions != ""
    ; split version strings on '\n'
    ; there must be at least one '\n', or WordFind finds no words
    StrCpy $0 0   ; word indices are 1-based
    ${Do}
      IntOp $0 $0 + 1
      ${WordFind} "$RemoveOtherVersions" "$\n" "E+$0" $1
      IfErrors 0 +2
      ${Break}

      DetailPrint "Uninstall: $1"

      ; look for 64-bit install
      ${If} ${BITS} == 64
        SetRegView 64

        ; get paths
        ReadRegStr $2 HKLM "${UNINST}\$1" "InstallLocation"
        ReadRegStr $3 HKLM "${UNINST}\$1" "UninstallString"
        IfErrors 0 found
        ClearErrors
      ${EndIf}

      ; look for 32-bit install
      SetRegView 32
      ReadRegStr $2 HKLM "${UNINST}\$1" "InstallLocation"
      ReadRegStr $3 HKLM "${UNINST}\$1" "UninstallString"
      IfErrors cleanup found

    found:
      IfFileExists "$3" 0 cleanup

      ; copy the uninstaller to $TEMP
      CopyFiles "$3" "$TEMP"
      ${GetFileName} $3 $4

      ; run the uninstaller silently
      ExecWait '"$TEMP\$4" /S _?=$2' $5
      IfErrors 0 +2
      DetailPrint "Failed with code $5"
      ClearErrors

      ; remove the uninstaller copy
      Delete "$TEMP\$4"

    cleanup:
      ClearErrors

    ${Loop}
  ${EndIf}

  SetRegView ${BITS}

  ; set the files to bundle
  !include "${INCDIR}/install_files.inc"

  ; write registry keys for uninstaller
  WriteRegStr HKLM "${UROOT}" "DisplayName" "Weather Map ${VERSION}"
  WriteRegStr HKLM "${UROOT}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${UROOT}" "DisplayIcon" "$INSTDIR\${APPEXE}"
  WriteRegStr HKLM "${UROOT}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UROOT}" "UninstallString" "$INSTDIR\uninst.exe"
  WriteRegStr HKLM "${UROOT}" "Publisher" "weathermap"
  WriteRegStr HKLM "${UROOT}" "URLInfoAbout" "https://github.com/delatbabel/weathermap"
  WriteRegStr HKLM "${UROOT}" "URLUpdateInfo" "https://github.com/delatbabel/weathermap"
  WriteRegDWORD HKLM "${UROOT}" "NoModify" 0x00000001
  WriteRegDWORD HKLM "${UROOT}" "NoRepair" 0x00000001

  ; create the uninstaller
  WriteUninstaller "$INSTDIR\uninst.exe"

  ; create the shortcuts
  ; don't use version number in shortcut names for Standard install
  ${If} $CustomSetup == 1
    StrCpy $0 "Weather Map ${VERSION}"
  ${Else}
    StrCpy $0 "Weather Map"
  ${EndIf}

  ; CreateShortCut uses $OUTDIR as the working directory for shortcuts
  SetOutPath "$INSTDIR"

  ; create the desktop shortcut
  ${If} $AddDesktopSC == 1
    CreateShortCut "$DESKTOP\$0.lnk" "$INSTDIR\${APPEXE}"
    WriteRegStr HKLM "${UROOT}" "DesktopShortcut" "$DESKTOP\$0.lnk"
  ${EndIf}

  !insertmacro MUI_STARTMENU_WRITE_BEGIN StartMenu
    ; create the Start Menu shortcut
    ${If} $AddStartMenuSC == 1
      CreateDirectory "$SMPROGRAMS\$StartMenuFolder"
      CreateShortCut "$SMPROGRAMS\$StartMenuFolder\$0.lnk" "$INSTDIR\${APPEXE}"
      WriteRegStr HKLM "${UROOT}" "StartMenuShortcut" "$SMPROGRAMS\$StartMenuFolder\$0.lnk"
    ${EndIf}
  !insertmacro MUI_STARTMENU_WRITE_END

  ; no file associations: the application fetches its own data
SectionEnd


#
# Uninstall Section
#
Section Uninstall
  SetRegView ${BITS}

  ; delete the uninstaller
  Delete "$INSTDIR\uninst.exe"

  ; delete the desktop shortcut
  ReadRegStr $0 HKLM "${UROOT}" "DesktopShortcut"
  ${If} $0 != ""
    Delete "$0"
  ${EndIf}

  ; delete the Start Menu items
  ReadRegStr $0 HKLM "${UROOT}" "StartMenuShortcut"
  ${If} $0 != ""
    Delete "$0"
  ${EndIf}

  ; delete the Start Menu folder
  !insertmacro MUI_STARTMENU_GETFOLDER StartMenu $StartMenuFolder
  RMDir "$SMPROGRAMS\$StartMenuFolder"

  ; delete the default folder from start menu if empty
  RMDir "$SMPROGRAMS\Weather Map"

  ; delete registry keys
  DeleteRegKey HKLM "${UROOT}"

  ; delete the installed files and directories
  !include "${INCDIR}/uninstall_files.inc"
SectionEnd
