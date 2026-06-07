#!/bin/sh
# Xcode Cloud post-clone hook. Xcode Cloud runs this from the
# `ci_scripts/` directory adjacent to the .xcodeproj it expects to
# build. We use the absence of the .xcodeproj here as the signal to
# generate it via xcodegen from `project.yml` (the source of truth).
#
# Required because we deliberately don't commit `Storehop.xcodeproj` —
# xcodegen regenerates it locally on every dev's machine + in CI.
# Without this script, Xcode Cloud fails with:
#   "Project Storehop.xcodeproj does not exist at ios/Storehop.xcodeproj"
#
# Mirrors the iOS CI workflow at .github/workflows/ios-ci.yml.

set -eux

# brew is the standard package manager on Xcode Cloud's macOS runners.
brew install xcodegen

# Xcode Cloud places this script at ci_scripts/ inside the repo; the
# project.yml lives one directory up.
cd "$CI_PRIMARY_REPOSITORY_PATH/ios"
xcodegen generate

# Optional: write a CI-only GoogleService-Info.plist so the build
# doesn't fail with a missing-Firebase-config error. Xcode Cloud
# secrets can replace this with a real plist via environment
# variable — see Xcode Cloud → Environment Variables → Files for the
# preferred secret-management path. For now this is a placeholder
# matching `.github/workflows/ios-ci.yml`'s inline plist.
if [ ! -f "Storehop/Resources/GoogleService-Info.plist" ]; then
  cat > Storehop/Resources/GoogleService-Info.plist <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>BUNDLE_ID</key><string>com.storehop.app</string>
  <key>API_KEY</key><string>ci-placeholder-api-key</string>
  <key>GOOGLE_APP_ID</key><string>1:000000000000:ios:0000000000000000000000</string>
  <key>GCM_SENDER_ID</key><string>000000000000</string>
  <key>PROJECT_ID</key><string>storehop-ci-placeholder</string>
  <key>STORAGE_BUCKET</key><string>storehop-ci-placeholder.appspot.com</string>
  <key>CLIENT_ID</key><string>000000000000-placeholder.apps.googleusercontent.com</string>
  <key>REVERSED_CLIENT_ID</key><string>com.googleusercontent.apps.000000000000-placeholder</string>
  <key>IS_ADS_ENABLED</key><false/>
  <key>IS_ANALYTICS_ENABLED</key><false/>
  <key>IS_APPINVITE_ENABLED</key><true/>
  <key>IS_GCM_ENABLED</key><true/>
  <key>IS_SIGNIN_ENABLED</key><true/>
</dict>
</plist>
PLIST
fi
