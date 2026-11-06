#!/usr/bin/env bash
# Captures the 8 Android 17 QPR2+ EmbeddedBiometricPrompt golden screenshots
# from the connected device/emulator using Multipaz Test App.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$SCRIPT_DIR/11_biometric_prompt"
mkdir -p "$OUT_DIR"

adb_cmd() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

echo "==> Ensuring disable_secure_windows is enabled on device..."
adb_cmd shell settings put secure disable_secure_windows 1

if [[ "${1:-}" != "--skip-install" ]]; then
  echo "==> Installing :samples:testapp:installBlueDebug..."
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :samples:testapp:installBlueDebug -Pdisable.web.targets=true
fi

echo "==> Launching Multipaz Test App..."
adb_cmd shell input keyevent KEYCODE_WAKEUP
sleep 0.5
adb_cmd shell wm dismiss-keyguard || true
adb_cmd shell input swipe 500 1800 500 400 200
sleep 0.5
adb_cmd shell am force-stop org.multipaz.testapp
adb_cmd shell am start -n org.multipaz.testapp/org.multipaz.testapp.MainActivity
sleep 3

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

dump_ui() {
  local out_xml="$1"
  adb_cmd shell uiautomator dump /sdcard/mpz_ui.xml >/dev/null
  adb_cmd shell cat /sdcard/mpz_ui.xml > "$out_xml"
}

tap_node_by_text() {
  local xml_file="$1"
  local target_text="$2"
  local coords
  coords="$(python3 - "$xml_file" "$target_text" << 'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml_path, target = sys.argv[1], sys.argv[2]
tree = ET.parse(xml_path)
for node in tree.iter("node"):
    if node.attrib.get("text") == target:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
            sys.exit(0)
sys.exit(1)
PY
)"
  adb_cmd shell input tap $coords
}

tap_change_for_prefix() {
  local xml_file="$1"
  local prefix="$2"
  local coords
  coords="$(python3 - "$xml_file" "$prefix" << 'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml_path, prefix = sys.argv[1], sys.argv[2]
tree = ET.parse(xml_path)
nodes = list(tree.iter("node"))
target_y = None
for node in nodes:
    text = node.attrib.get("text", "")
    if text.startswith(prefix):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if m:
            _, y1, _, y2 = map(int, m.groups())
            target_y = (y1 + y2) / 2.0
            break
if target_y is None:
    sys.exit(1)

best = None
best_dist = 1e9
for node in nodes:
    if node.attrib.get("text") == "Change":
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            cy = (y1 + y2) / 2.0
            dist = abs(cy - target_y)
            if dist < best_dist:
                best_dist = dist
                best = ((x1 + x2) // 2, (y1 + y2) // 2)
if best is None:
    sys.exit(1)
print(f"{best[0]} {best[1]}")
PY
)"
  adb_cmd shell input tap $coords
}

cat > "$TMP_DIR/CropBiometric.java" << 'JAVA'
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class CropBiometric {
    public static void main(String[] args) throws Exception {
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        int topY = Integer.parseInt(args[2]);
        int bottomY = Integer.parseInt(args[3]);

        BufferedImage src = ImageIO.read(inFile);
        int width = src.getWidth();
        topY = Math.max(0, Math.min(topY, src.getHeight() - 1));
        bottomY = Math.max(topY + 1, Math.min(bottomY, src.getHeight()));
        int height = bottomY - topY;

        BufferedImage cropped = src.getSubimage(0, topY, width, height);
        ImageIO.write(cropped, "PNG", outFile);
    }
}
JAVA

echo "==> Navigating to Consent prompt use-cases..."
dump_ui "$TMP_DIR/main.xml"
tap_node_by_text "$TMP_DIR/main.xml" "Consent prompt use-cases"
sleep 1

capture_combination() {
  local content_label="$1"
  local requester_label="$2"
  local scenario_id="$3"

  echo "==> Capturing $scenario_id ($requester_label | $content_label)..."
  dump_ui "$TMP_DIR/screen.xml"
  tap_change_for_prefix "$TMP_DIR/screen.xml" "Content:"
  sleep 0.6
  dump_ui "$TMP_DIR/menu_content.xml"
  tap_node_by_text "$TMP_DIR/menu_content.xml" "$content_label"
  sleep 0.6

  dump_ui "$TMP_DIR/screen.xml"
  tap_change_for_prefix "$TMP_DIR/screen.xml" "Requester:"
  sleep 0.6
  dump_ui "$TMP_DIR/menu_requester.xml"
  tap_node_by_text "$TMP_DIR/menu_requester.xml" "$requester_label"
  sleep 0.6

  # Scroll slightly so the background behind the top corners of the biometric sheet is clean
  adb_cmd shell input swipe 500 800 500 680 200
  sleep 0.5

  dump_ui "$TMP_DIR/screen.xml"
  tap_node_by_text "$TMP_DIR/screen.xml" "Show Consent Prompt"
  sleep 0.7

  local raw_png="$TMP_DIR/${scenario_id}_raw.png"
  adb_cmd exec-out screencap -p > "$raw_png"

  dump_ui "$TMP_DIR/biometric.xml"
  read -r panel_top btn_bottom close_x close_y < <(python3 - "$TMP_DIR/biometric.xml" << 'PY'
import re
import sys
import xml.etree.ElementTree as ET

tree = ET.parse(sys.argv[1])
panel_top = 998
btn_bottom = 2190
width = 1008
for node in tree.iter("node"):
    rid = node.attrib.get("resource-id", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    if rid == "com.android.systemui:id/panel":
        panel_top = y1
        width = x2
    elif rid == "com.android.systemui:id/button_bar":
        btn_bottom = y2
close_x = width - 72
close_y = panel_top + 72
print(f"{panel_top} {btn_bottom} {close_x} {close_y}")
PY
)

  # Dismiss EmbeddedBiometricPrompt via top-right X button
  adb_cmd shell input tap "$close_x" "$close_y"
  sleep 0.8

  # Restore scroll position
  adb_cmd shell input swipe 500 680 500 800 200
  sleep 0.5

  local out_png="$OUT_DIR/${scenario_id}__main.png"
  java "$TMP_DIR/CropBiometric.java" "$raw_png" "$out_png" "$panel_top" "$btn_bottom"
  echo "    Wrote $out_png (panel_top=$panel_top, btn_bottom=$btn_bottom)"
}

capture_combination "mDL: Age over 21" "Utopia Marketplace (w/ privacy policy)" "trusted_requester_age_over_21"
capture_combination "mDL: Age over 21 + portrait" "Utopia Marketplace (w/ privacy policy)" "trusted_requester_age_over_21_and_portrait"
capture_combination "mDL: Age over 21" "Dave's Bookstore (Not in trust list)" "unknown_requester_age_over_21"
capture_combination "mDL: Age over 21 + portrait" "Dave's Bookstore (Not in trust list)" "unknown_requester_age_over_21_and_portrait"
capture_combination "mDL: Age over 21" "None" "anonymous_requester_age_over_21"
capture_combination "mDL: Age over 21 + portrait" "None" "anonymous_requester_age_over_21_and_portrait"
capture_combination "mDL: Age over 50 + portrait" "Utopia Marketplace (w/ privacy policy)" "trusted_requester_age_over_50_and_portrait"
capture_combination "mDL: Age over 65 + portrait" "Utopia Marketplace (w/ privacy policy)" "trusted_requester_age_over_65_and_portrait"

echo "==> Done! All 8 biometric prompt golden screenshots updated in $OUT_DIR."
