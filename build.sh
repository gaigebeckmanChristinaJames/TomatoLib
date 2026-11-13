#!/bin/bash
set -e
BT=${BT:-$HOME/android-sdk/build-tools/34.0.0}
AJ=${AJ:-$HOME/android-sdk/platforms/android-34/android.jar}
JAVAC=${JAVAC:-javac}
OUT=build
VER=1.6.8
APK=TomatoLib-v$VER.apk

rm -rf $OUT && mkdir -p $OUT/res $OUT/classes $OUT/dex

$BT/aapt2 compile --dir src/main/res -o $OUT/res/res.zip

$BT/aapt2 link -o $OUT/base.apk -I $AJ \
  --manifest src/main/AndroidManifest.xml \
  -A assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --auto-add-overlay $OUT/res/res.zip

$JAVAC -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 \
  -d $OUT/classes -cp "$AJ" $(find src/main/java -name '*.java')

$BT/d8 --min-api 26 --lib $AJ --output $OUT/dex $(find $OUT/classes -name '*.class')

cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q -X ../unsigned.apk classes.dex)

$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
: "${KSPASS:?先设置 KSPASS=pass:你的口令}"; : "${KEYPASS:=$KSPASS}"
$BT/apksigner sign --ks "${KS:-keystore.jks}" --ks-pass "$KSPASS" --key-pass "$KEYPASS" \
  --out $APK $OUT/aligned.apk
$BT/apksigner verify --print-certs $APK | head -3
ls -l $APK
