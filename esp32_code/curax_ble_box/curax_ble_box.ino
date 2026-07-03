/*
  CuraX - dual zone + system DHT + OLED + LCD
  -------------------------------------------
  SYSTEM DHT11  GPIO 5  -> OLED (poora box / ambient)
  ZONE 1  DHT11  GPIO 23 -> LCD row 1
  ZONE 2  DS18B20 GPIO 4 -> LCD row 2 (4.7k pullup DATA-VCC)

  I2C SDA 21, SCL 22 -> OLED 0x3C + LCD 0x27  (PCA YAHAN NA LAGAO)
  I2C SDA 18, SCL 19 -> PCA9685 0x40 (servos)   (alag bus — lazy init)

  Relay: IN1=GPIO2 fans | IN2=GPIO15 cooler
  Serial (PIN ke baad): 1/2=fans  3/4=cooler
    a-f = drawer CH0..CH5 open (Serial ONLY — ek waqt 1 hi)
    0   = active drawer band (Serial)
    5/6 = CH0 open/close (Serial)
  Keypad: sirf PIN 147 + *

  Screen flow:
    0 Opening CuraX (2.5s) -> 1 Enter PIN -> 2 System Started (2.5s) -> 3 Running
  BLE PIN_UNLOCK:147 — app se box unlock (keypad jaisa)
  BLE TEMP_SET:peltier1/2,enabled,target -> auto fans/cooler when T > target
  BLE SERVO_OPEN:B1..B6 / SERVO_CLOSE / SERVO_ALL_CLOSE (dose tab, PIN unlock ke baad)

  Keypad rows → 26, 25, 33, 32 | cols → 13, 12, 14, 27
  PIN 147 + * submit (keypad YA Serial Monitor)

  LEDs / IO expander: abhi band (baad mein add karenge)
*/

#define ENABLE_PCA9685 1  // 0 = sirf displays test, 1 = PCA drawer on GPIO 18/19

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Keypad.h>
#include <DHT.h>
#include <LiquidCrystal_I2C.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#if ENABLE_PCA9685
#include <Adafruit_PWMServoDriver.h>
#endif

#define OLED_W 128
#define OLED_H 64
#define PIN_I2C_SDA 21
#define PIN_I2C_SCL 22
#define PIN_PCA_SDA 18
#define PIN_PCA_SCL 19
#define OLED_I2C_ADDR 0x3C
#define LCD_I2C_ADDR 0x27  // auto: 0x27 ya 0x3F

#define PIN_DHT_SYSTEM 5   // system DHT11 -> OLED
#define PIN_DHT_ZONE1 23   // zone 1 DHT11 -> LCD row 1
#define PIN_DS18B20 4      // zone 2 DS18B20 -> LCD row 2
#define PCA9685_ADDR 0x40
#define NUM_SERVOS 6

LiquidCrystal_I2C *lcd = nullptr;
uint8_t lcdAddr = 0;
static LiquidCrystal_I2C lcd27(0x27, 16, 2);
static LiquidCrystal_I2C lcd3f(0x3F, 16, 2);
Adafruit_SSD1306 oled(OLED_W, OLED_H, &Wire, -1);
#if ENABLE_PCA9685
TwoWire WirePca = TwoWire(1);
Adafruit_PWMServoDriver *pwmDrv = nullptr;
#endif
String pcaBusLabel = "";
DHT dhtSystem(PIN_DHT_SYSTEM, DHT11);
DHT dhtZone1(PIN_DHT_ZONE1, DHT11);
OneWire oneWire(PIN_DS18B20);
DallasTemperature zone2Sensor(&oneWire);

const int OPEN_POS = 22;
const int CLOSE_POS = 110;
const int SERVO_TICK_MIN = 150;
const int SERVO_TICK_MAX = 600;
const int SERVO_STEP_MS = 18;
int servoPos[NUM_SERVOS];
int activeOut = -1;
bool pcaOk = false;

bool oledOk = false;
bool lcdOk = false;

// Relay CH1=fans IN1=GPIO2 | CH2=cooler IN2=GPIO15
const int RELAY_PIN_CH1 = 2;
const int RELAY_PIN_CH2 = 15;
const bool RELAY_ACTIVE_LOW = true;
bool fansOn = false;
bool coolerOn = false;

const char *bleName = "CuraX-Box";
BLEUUID bleService("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
BLEUUID bleRxUuid("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
BLEUUID bleTxUuid("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

BLECharacteristic *bleTx = nullptr;
bool phoneConnected = false;
String bleLine = "";

char keyMap[4][4] = {
  {'1', '2', '3', 'A'},
  {'4', '5', '6', 'B'},
  {'7', '8', '9', 'C'},
  {'*', '0', '#', 'D'}};
byte rowPins[4] = {26, 25, 33, 32};
byte colPins[4] = {13, 12, 14, 27};
Keypad keypad = Keypad(makeKeymap(keyMap), rowPins, colPins, 4, 4);

const char *correctPin = "147";
String typedPin = "";

int screenStep = 0;
unsigned long stepTime = 0;
unsigned long lastSensorMs = 0;

float systemTemp = NAN;
float systemHum = NAN;
float zone1Temp = NAN;
float zone1Hum = NAN;
float zone2Temp = NAN;

// Auto cooling: zone1 TEMP1 -> fans (CH1) | zone2 TEMP2 -> cooler (CH2)
bool zone1AutoEnabled = false;
bool zone2AutoEnabled = false;
float zone1TargetC = 25.0f;
float zone2TargetC = 25.0f;
const float TEMP_HYSTERESIS_C = 1.0f;

void ensureDisplayBus();
void showRunning();
void showPinScreen();
bool initPca9685();

int posToTick(int angle) {
  return map(constrain(angle, 0, 180), 0, 180, SERVO_TICK_MIN, SERVO_TICK_MAX);
}

void pwmOnly(int ch, int tick) {
#if !ENABLE_PCA9685
  (void)ch;
  (void)tick;
  return;
#else
  if (!pcaOk || !pwmDrv || ch < 0 || ch >= NUM_SERVOS) return;
  pwmDrv->setPWM(ch, 0, constrain(tick, SERVO_TICK_MIN, SERVO_TICK_MAX));
#endif
}

void allChannelsCloseNow() {
#if ENABLE_PCA9685
  if (!pcaOk || !pwmDrv) return;
  int closeTick = posToTick(CLOSE_POS);
  for (int i = 0; i < NUM_SERVOS; i++) {
    pwmOnly(i, closeTick);
    servoPos[i] = CLOSE_POS;
  }
  activeOut = -1;
#endif
}

void moveOneChannel(int ch, int fromAngle, int toAngle) {
#if !ENABLE_PCA9685
  (void)ch;
  (void)fromAngle;
  (void)toAngle;
  return;
#else
  if (!pcaOk || !pwmDrv) return;

  fromAngle = constrain(fromAngle, OPEN_POS, CLOSE_POS);
  toAngle = constrain(toAngle, OPEN_POS, CLOSE_POS);
  if (fromAngle == toAngle) return;

  int fromTick = posToTick(fromAngle);
  int toTick = posToTick(toAngle);

  Serial.print("MOVE CH");
  Serial.print(ch);
  Serial.print(" ");
  Serial.print(fromAngle);
  Serial.print("->");
  Serial.println(toAngle);

  if (fromTick < toTick) {
    for (int t = fromTick + 1; t <= toTick; t++) {
      pwmOnly(ch, t);
      delay(SERVO_STEP_MS);
      yield();
    }
  } else {
    for (int t = fromTick - 1; t >= toTick; t--) {
      pwmOnly(ch, t);
      delay(SERVO_STEP_MS);
      yield();
    }
  }
  servoPos[ch] = toAngle;
#endif
}

void drawerOpen(int ch) {
#if !ENABLE_PCA9685
  Serial.println("PCA disabled — ENABLE_PCA9685=1 + wires 18/19");
  return;
#else
  if (ch < 0 || ch >= NUM_SERVOS) return;
  if (!pcaOk && !initPca9685()) {
    Serial.println("PCA9685 not ready (18/19 only — 21/22 par mat lagao)");
    return;
  }

  if (activeOut == ch) {
    Serial.print("CH");
    Serial.print(ch);
    Serial.println(" already open");
    return;
  }

  if (activeOut >= 0 && activeOut != ch) {
    moveOneChannel(activeOut, servoPos[activeOut], CLOSE_POS);
    activeOut = -1;
  }

  moveOneChannel(ch, servoPos[ch], OPEN_POS);
  activeOut = ch;
  ensureDisplayBus();
  if (screenStep >= 3) showRunning();
  Serial.println("Drawer opened");
#endif
}

void drawerClose(int ch) {
#if !ENABLE_PCA9685
  (void)ch;
  return;
#else
  if (ch < 0 || ch >= NUM_SERVOS) return;
  if (!pcaOk && !initPca9685()) return;

  moveOneChannel(ch, servoPos[ch], CLOSE_POS);
  if (activeOut == ch) activeOut = -1;
  ensureDisplayBus();
  if (screenStep >= 3) showRunning();
  Serial.println("Drawer closed");
#endif
}

void drawerCloseActive() {
  if (activeOut < 0) {
    Serial.println("Koi drawer open nahi");
    return;
  }
  drawerClose(activeOut);
}

int letterToCh(char c) {
  if (c >= 'a' && c <= 'f') return c - 'a';
  if (c >= 'A' && c <= 'F') return c - 'A';
  return -1;
}

// App dose tab: B1..B6 -> PCA CH0..CH5 (same as Serial a..f)
void bleSend(String msg);

int boxIdToCh(const String &boxId) {
  String b = boxId;
  b.trim();
  b.toUpperCase();
  if (b.length() != 2 || b.charAt(0) != 'B') return -1;
  int n = b.charAt(1) - '0';
  if (n < 1 || n > NUM_SERVOS) return -1;
  return n - 1;
}

void handleServoOpenBle(const String &boxId) {
  if (screenStep < 3) {
    bleSend("ERR:LOCKED");
    return;
  }
  String id = boxId;
  id.trim();
  id.toUpperCase();
  int ch = boxIdToCh(id);
  if (ch < 0) {
    bleSend("ERR:SERVO_BOX");
    return;
  }
  drawerOpen(ch);
  bleSend("OK:SERVO_OPEN:" + id);
}

void handleServoCloseBle(const String &boxId) {
  if (screenStep < 3) {
    bleSend("ERR:LOCKED");
    return;
  }
  String id = boxId;
  id.trim();
  id.toUpperCase();
  int ch = boxIdToCh(id);
  if (ch < 0) {
    bleSend("ERR:SERVO_BOX");
    return;
  }
  drawerClose(ch);
  bleSend("OK:SERVO_CLOSE:" + id);
}

void handleServoAllCloseBle() {
  if (screenStep < 3) {
    bleSend("ERR:LOCKED");
    return;
  }
  drawerCloseActive();
  bleSend("OK:SERVO_ALL_CLOSE");
}

bool i2cDeviceAt(TwoWire &bus, uint8_t addr) {
  bus.beginTransmission(addr);
  uint8_t err = bus.endTransmission();
  return err == 0;
}

void reportI2cPinLevels() {
  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  pinMode(PIN_I2C_SCL, INPUT_PULLUP);
  Serial.print("I2C pin levels SDA(21)=");
  Serial.print(digitalRead(PIN_I2C_SDA));
  Serial.print(" SCL(22)=");
  Serial.print(digitalRead(PIN_I2C_SCL));
  Serial.println("  (idle par dono 1 hone chahiye)");
}

// SDA stuck low ho to 9 clock pulses se slave release
void recoverI2cBusHard() {
  Serial.println("I2C hard recover GPIO 21/22...");
  Wire.end();
  delay(20);

  pinMode(PIN_I2C_SCL, OUTPUT);
  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  digitalWrite(PIN_I2C_SCL, HIGH);
  delayMicroseconds(10);

  for (int i = 0; i < 9; i++) {
    digitalWrite(PIN_I2C_SCL, LOW);
    delayMicroseconds(8);
    digitalWrite(PIN_I2C_SCL, HIGH);
    delayMicroseconds(8);
  }

  pinMode(PIN_I2C_SDA, OUTPUT);
  digitalWrite(PIN_I2C_SDA, LOW);
  delayMicroseconds(8);
  digitalWrite(PIN_I2C_SCL, HIGH);
  delayMicroseconds(8);
  digitalWrite(PIN_I2C_SDA, HIGH);
  delayMicroseconds(8);

  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  pinMode(PIN_I2C_SCL, INPUT_PULLUP);
  delay(30);

  initI2cBus();
  reportI2cPinLevels();
}

bool startPcaDriver(const char *label) {
#if !ENABLE_PCA9685
  (void)label;
  return false;
#else
  if (pwmDrv) {
    delete pwmDrv;
    pwmDrv = nullptr;
  }
  pwmDrv = new Adafruit_PWMServoDriver(PCA9685_ADDR, WirePca);
  pwmDrv->begin();
  pwmDrv->setPWMFreq(50);
  delay(10);
  pcaBusLabel = label;
  pcaOk = true;
  allChannelsCloseNow();
  Serial.print("PCA9685 ready @ ");
  Serial.println(label);
  ensureDisplayBus();
  return true;
#endif
}

void scanBusDevices(TwoWire &bus, const char *label) {
  Serial.print("I2C scan ");
  Serial.print(label);
  Serial.println(":");
  byte n = 0;
  for (byte a = 1; a < 127; a++) {
    if (i2cDeviceAt(bus, a)) {
      Serial.print("  0x");
      if (a < 16) Serial.print('0');
      Serial.println(a, HEX);
      n++;
    }
  }
  if (n == 0) Serial.println("  (koi device nahi)");
}

bool initPca9685() {
#if !ENABLE_PCA9685
  return false;
#else
  pcaOk = false;
  pcaBusLabel = "";
  if (pwmDrv) {
    delete pwmDrv;
    pwmDrv = nullptr;
  }

  pinMode(PIN_PCA_SDA, INPUT_PULLUP);
  pinMode(PIN_PCA_SCL, INPUT_PULLUP);
  WirePca.begin(PIN_PCA_SDA, PIN_PCA_SCL);
  WirePca.setClock(50000);
  delay(50);

  if (i2cDeviceAt(WirePca, PCA9685_ADDR)) {
    return startPcaDriver("GPIO 18/19");
  }

  ensureDisplayBus();
  Serial.println("PCA9685 NOT FOUND on 18/19 (displays 21/22 safe)");
  return false;
#endif
}

void scanPcaBus18_19() {
#if !ENABLE_PCA9685
  Serial.println("PCA disabled");
  return;
#else
  pinMode(PIN_PCA_SDA, INPUT_PULLUP);
  pinMode(PIN_PCA_SCL, INPUT_PULLUP);
  WirePca.begin(PIN_PCA_SDA, PIN_PCA_SCL);
  WirePca.setClock(50000);
  delay(100);
  WirePca.beginTransmission(0x40);
  uint8_t err = WirePca.endTransmission();
  Serial.print("0x40 on 18/19: code ");
  Serial.println(err);
  scanBusDevices(WirePca, "GPIO 18/19");
  ensureDisplayBus();
#endif
}

void relayWritePin(int pin, bool on) {
  if (RELAY_ACTIVE_LOW) {
    digitalWrite(pin, on ? LOW : HIGH);
  } else {
    digitalWrite(pin, on ? HIGH : LOW);
  }
}

void relayOffIdle(int pin) {
  if (RELAY_ACTIVE_LOW) {
    digitalWrite(pin, HIGH);
  } else {
    digitalWrite(pin, LOW);
  }
}

void initRelays() {
  pinMode(RELAY_PIN_CH1, OUTPUT);
  pinMode(RELAY_PIN_CH2, OUTPUT);
  relayOffIdle(RELAY_PIN_CH1);
  relayOffIdle(RELAY_PIN_CH2);
  fansOn = false;
  coolerOn = false;
  Serial.println("Relay IN1=GPIO2 (fans)  IN2=GPIO15 (cooler)");
}

void preparePcaPinsIdle() {
#if ENABLE_PCA9685
  pinMode(PIN_PCA_SDA, INPUT_PULLUP);
  pinMode(PIN_PCA_SCL, INPUT_PULLUP);
#endif
}

void setFans(bool on) {
  fansOn = on;
  relayWritePin(RELAY_PIN_CH1, on);
  Serial.print("CH1 Fans: ");
  Serial.println(on ? "ON" : "OFF");
}

void setCooler(bool on) {
  coolerOn = on;
  relayWritePin(RELAY_PIN_CH2, on);
  Serial.print("CH2 Cooler: ");
  Serial.println(on ? "ON" : "OFF");
}

// T > target -> ON (cool down). T <= target - hysteresis -> OFF.
void applyTempControl() {
  if (screenStep < 3) {
    Serial.println("AUTO temp: PIN unlock ke baad chalega (screenStep<3)");
    return;
  }

  if (zone1AutoEnabled && !isnan(zone1Temp)) {
    if (zone1Temp > zone1TargetC) {
      setFans(true);
    } else if (zone1Temp <= zone1TargetC - TEMP_HYSTERESIS_C) {
      setFans(false);
    }
  } else if (zone1AutoEnabled) {
    Serial.println("AUTO Z1: enabled, sensor reading nahi");
  }

  if (zone2AutoEnabled && !isnan(zone2Temp)) {
    Serial.print("AUTO Z2 T=");
    Serial.print(zone2Temp, 1);
    Serial.print(" target=");
    Serial.print(zone2TargetC, 1);
    if (zone2Temp > zone2TargetC) {
      setCooler(true);
      Serial.println(" -> COOLER ON");
    } else if (zone2Temp <= zone2TargetC - TEMP_HYSTERESIS_C) {
      setCooler(false);
      Serial.println(" -> COOLER OFF");
    } else {
      Serial.print(" -> COOLER ");
      Serial.println(coolerOn ? "hold ON" : "hold OFF");
    }
  } else if (zone2AutoEnabled) {
    Serial.println("AUTO Z2: enabled, sensor reading nahi");
  }
}

void handleTempSet(const String &cmd) {
  int colon = cmd.indexOf(':');
  if (colon < 0) {
    bleSend("ERR:TEMP_SET");
    return;
  }
  String rest = cmd.substring(colon + 1);
  rest.trim();
  int c1 = rest.indexOf(',');
  int c2 = rest.indexOf(',', c1 + 1);
  if (c1 < 0 || c2 < 0) {
    bleSend("ERR:TEMP_SET");
    return;
  }

  String zone = rest.substring(0, c1);
  zone.trim();
  zone.toLowerCase();
  String enStr = rest.substring(c1 + 1, c2);
  enStr.trim();
  String targetStr = rest.substring(c2 + 1);
  targetStr.trim();

  bool en = enStr.equalsIgnoreCase("true") || enStr == "1";
  float target = targetStr.toFloat();

  if (zone == "peltier1") {
    zone1AutoEnabled = en;
    zone1TargetC = target;
    if (!en && fansOn) setFans(false);
    Serial.print("TEMP_SET Z1 fans target=");
    Serial.print(target, 1);
    Serial.print(" enabled=");
    Serial.println(en);
  } else if (zone == "peltier2") {
    zone2AutoEnabled = en;
    zone2TargetC = target;
    if (!en && coolerOn) setCooler(false);
    Serial.print("TEMP_SET Z2 cooler target=");
    Serial.print(target, 1);
    Serial.print(" enabled=");
    Serial.println(en);
  } else {
    bleSend("ERR:TEMP_SET_ZONE");
    return;
  }

  readAllSensors();
  applyTempControl();
  bleSend("OK:TEMP_SET:" + zone);
  if (zone == "peltier2") {
    bleSend(String("OK:COOLER:") + (coolerOn ? "ON" : "OFF"));
  } else if (zone == "peltier1") {
    bleSend(String("OK:FANS:") + (fansOn ? "ON" : "OFF"));
  }
}

void initI2cBus() {
  Wire.end();
  delay(10);
  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  pinMode(PIN_I2C_SCL, INPUT_PULLUP);
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  Wire.setClock(50000);
  Wire.setTimeOut(50);
  delay(50);
}

void ensureDisplayBus() {
  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  pinMode(PIN_I2C_SCL, INPUT_PULLUP);
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  Wire.setClock(50000);
  Wire.setTimeOut(50);
}

// PCA kabhi 21/22 par na ho — yahi se LCD/OLED NACK shuru hua tha
void warnIfPcaOnDisplayBus() {
  if (i2cDeviceAt(Wire, PCA9685_ADDR)) {
    Serial.println("!!! PCA 0x40 on bus 21/22 — GALAT wiring !!!");
    Serial.println("    PCA SDA/SCL -> GPIO 18/19 move karo");
    Serial.println("    OLED+LCD sirf GPIO 21/22 par rahein");
  }
}

// Boot se pehle: relay OFF, keypad/PCA/I2C pull-up — koi pin short na pakre
void releaseStuckPins() {
  pinMode(RELAY_PIN_CH1, OUTPUT);
  pinMode(RELAY_PIN_CH2, OUTPUT);
  relayOffIdle(RELAY_PIN_CH1);
  relayOffIdle(RELAY_PIN_CH2);

  for (byte i = 0; i < 4; i++) {
    pinMode(rowPins[i], INPUT_PULLUP);
    pinMode(colPins[i], INPUT_PULLUP);
  }

  preparePcaPinsIdle();
  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  pinMode(PIN_I2C_SCL, INPUT_PULLUP);
  pinMode(PIN_DHT_SYSTEM, INPUT);
  pinMode(PIN_DHT_ZONE1, INPUT);
  pinMode(PIN_DS18B20, INPUT);
}

void resetBootState() {
  screenStep = 0;
  typedPin = "";
  stepTime = 0;
  lastSensorMs = 0;
  activeOut = -1;
  pcaOk = false;
  pwmDrv = nullptr;
  pcaBusLabel = "";
  oledOk = false;
  lcdOk = false;
  lcd = nullptr;
  lcdAddr = 0;
  phoneConnected = false;
  bleLine = "";
  fansOn = false;
  coolerOn = false;
  systemTemp = NAN;
  systemHum = NAN;
  zone1Temp = NAN;
  zone1Hum = NAN;
  zone2Temp = NAN;
  for (int i = 0; i < NUM_SERVOS; i++) {
    servoPos[i] = CLOSE_POS;
  }
#if ENABLE_PCA9685
  if (pwmDrv) {
    delete pwmDrv;
    pwmDrv = nullptr;
  }
#endif
}

void scanI2cBus() {
  Serial.println("I2C scan 21/22 (OLED+LCD):");
  byte n = 0;
  for (byte a = 1; a < 127; a++) {
    if (i2cDeviceAt(Wire, a)) {
      Serial.print("  found 0x");
      if (a < 16) Serial.print('0');
      Serial.println(a, HEX);
      n++;
    }
  }
  if (n == 0) Serial.println("  (koi device nahi)");
}

void scanI2cBootCheck() {
  Serial.println("I2C boot check 21/22:");
  const uint8_t addrs[] = {0x3C, 0x3D, 0x27, 0x3F};
  byte n = 0;
  for (byte i = 0; i < 4; i++) {
    if (i2cDeviceAt(Wire, addrs[i])) {
      Serial.print("  found 0x");
      Serial.println(addrs[i], HEX);
      n++;
    }
  }
  if (n == 0) Serial.println("  (koi device nahi — wiring check karo)");
}

uint8_t findLcdAddr() {
  if (i2cDeviceAt(Wire, 0x27)) return 0x27;
  if (i2cDeviceAt(Wire, 0x3F)) return 0x3F;
  return 0;
}

void initOled() {
  oledOk = false;
  if (i2cDeviceAt(Wire, OLED_I2C_ADDR) &&
      oled.begin(SSD1306_SWITCHCAPVCC, OLED_I2C_ADDR)) {
    oledOk = true;
    oled.clearDisplay();
    oled.display();
    Serial.println("OLED ready 0x3C");
    return;
  }
  if (i2cDeviceAt(Wire, 0x3D) &&
      oled.begin(SSD1306_SWITCHCAPVCC, 0x3D)) {
    oledOk = true;
    oled.clearDisplay();
    oled.display();
    Serial.println("OLED ready 0x3D");
    return;
  }
  Serial.println("OLED not found");
}

void initLcd() {
  lcdOk = false;
  lcd = nullptr;
  lcdAddr = findLcdAddr();
  if (lcdAddr == 0) {
    Serial.println("LCD NOT found — check SDA=21 SCL=22, addr 0x27/0x3F");
    return;
  }
  if (lcdAddr == 0x27) lcd = &lcd27;
  else lcd = &lcd3f;

  lcd->init();
  delay(80);
  lcd->backlight();
  lcd->clear();

  if (!i2cDeviceAt(Wire, lcdAddr)) {
    Serial.println("LCD lost after init");
    lcd = nullptr;
    return;
  }

  lcdOk = true;
  Serial.print("LCD ready 0x");
  Serial.println(lcdAddr, HEX);
}

void initDisplays() {
  initOled();
  initLcd();

  // EN reset: LCD module ESP32 se alag reset hota nahi — dobara init
  if (lcdOk) {
    delay(60);
    lcd->init();
    lcd->backlight();
    lcd->clear();
  }
  if (oledOk) {
    oled.clearDisplay();
    oled.display();
  }
}

String lcdZone1Line() {
  String s = "Z1:";
  if (isnan(zone1Temp)) s += "--";
  else s += String(zone1Temp, 1);
  s += "C H:";
  if (isnan(zone1Hum)) s += "--";
  else s += String((int)zone1Hum);
  return s;
}

String lcdZone2Line() {
  String s = "Z2:";
  if (isnan(zone2Temp)) s += "--";
  else s += String(zone2Temp, 1);
  s += "C";
  return s;
}

void lcdWriteLine(int row, const String &text) {
  if (!lcdOk || !lcd || row < 0 || row > 1) return;
  lcd->setCursor(0, row);
  for (int i = 0; i < 16; i++) {
    if (i < (int)text.length()) lcd->print(text.charAt(i));
    else lcd->print(' ');
  }
}

void oledShowCenter(const char *line1, const char *line2 = "") {
  if (!oledOk) return;
  oled.clearDisplay();
  oled.setTextSize(1);
  oled.setTextColor(SSD1306_WHITE);
  oled.setCursor(0, 22);
  oled.println(line1);
  if (line2[0] != '\0') {
    oled.setCursor(0, 38);
    oled.println(line2);
  }
  oled.display();
}

void showCenter(const char *line1, const char *line2 = "") {
  if (lcdOk) {
    lcd->clear();
    lcdWriteLine(0, String(line1));
    if (line2[0] != '\0') lcdWriteLine(1, String(line2));
  }
  oledShowCenter(line1, line2);
}

void startOpeningScreen() {
  screenStep = 0;
  stepTime = millis();
  showCenter("Opening CuraX", "Please wait...");
}

// EN dabane par ESP32 reset hota hai, LCD nahi — Serial se L se wapas lao
void recoverDisplays() {
  Serial.println("LCD/OLED recover...");
  recoverI2cBusHard();
  initDisplays();
  if (screenStep == 0) showCenter("Opening CuraX", "Please wait...");
  else if (screenStep == 1) showPinScreen();
  else if (screenStep == 2) showCenter("System Started");
  else showRunning();
  Serial.print("Recover OLED=");
  Serial.print(oledOk ? "OK" : "NO");
  Serial.print(" LCD=");
  Serial.println(lcdOk ? "OK" : "NO");
  if (!oledOk && !lcdOk) {
    Serial.println("Bus dead — LCD power cycle (5V off/on) ya EN reset karo");
  }
}

void showPinScreen() {
  if (lcdOk) {
    lcd->clear();
  }
  lcdWriteLine(0, "Enter Your PIN");
  if (typedPin.length() == 0) {
    lcdWriteLine(1, "Press * to enter");
  } else {
    String pinLine = "PIN: ";
    for (unsigned int i = 0; i < typedPin.length() && pinLine.length() < 16; i++) {
      pinLine += '*';
    }
    lcdWriteLine(1, pinLine);
  }

  if (!oledOk) return;
  oled.clearDisplay();
  oled.setTextSize(1);
  oled.setCursor(0, 0);
  oled.println("Enter Your PIN");
  oled.drawLine(0, 12, 127, 12, SSD1306_WHITE);
  oled.setCursor(0, 20);
  oled.print("PIN: ");
  for (unsigned int i = 0; i < typedPin.length(); i++) oled.print('*');
  oled.setCursor(0, 48);
  oled.println("Press * to enter");
  oled.display();
}

// Running: OLED=system DHT | LCD=Zone1 + Zone2
void showRunning() {
  if (oledOk) {
    oled.clearDisplay();
    oled.setTextSize(1);
    oled.setCursor(0, 0);
    oled.println("CuraX Running");
    oled.drawLine(0, 10, 127, 10, SSD1306_WHITE);
    oled.setCursor(0, 14);
    oled.println("System DHT11");
    oled.setCursor(0, 26);
    oled.print("Temp: ");
    if (isnan(systemTemp)) oled.print("--");
    else oled.print(systemTemp, 1);
    oled.println(" C");
    oled.setCursor(0, 38);
    oled.print("Hum:  ");
    if (isnan(systemHum)) oled.print("--");
    else oled.print((int)systemHum);
    oled.println(" %");
    oled.setCursor(0, 50);
    oled.print("Drawer:");
    if (activeOut < 0) oled.println("Closed");
    else {
      oled.print("CH");
      oled.println(activeOut);
    }
    oled.setCursor(0, 58);
    oled.print("BLE:");
    oled.println(phoneConnected ? "OK" : "--");
    oled.display();
  }

  if (lcdOk) {
    lcdWriteLine(0, lcdZone1Line());
    lcdWriteLine(1, lcdZone2Line());
  }
}

void readSystemOnce() {
  float t = dhtSystem.readTemperature();
  float h = dhtSystem.readHumidity();
  if (!isnan(t)) systemTemp = t;
  if (!isnan(h)) systemHum = h;
}

void readZone1Once() {
  float t = dhtZone1.readTemperature();
  float h = dhtZone1.readHumidity();
  if (!isnan(t)) zone1Temp = t;
  if (!isnan(h)) zone1Hum = h;
}

void readZone2Once() {
  zone2Sensor.requestTemperatures();
  float t = zone2Sensor.getTempCByIndex(0);
  if (t != DEVICE_DISCONNECTED_C && t > -100.0f && t < 125.0f) {
    zone2Temp = t;
  }
}

void readSystem() {
  for (int i = 0; i < 3; i++) {
    readSystemOnce();
    if (!isnan(systemTemp) && !isnan(systemHum)) return;
    delay(2000);
  }
}

void readZone1() {
  for (int i = 0; i < 3; i++) {
    readZone1Once();
    if (!isnan(zone1Temp) && !isnan(zone1Hum)) return;
    delay(2000);
  }
}

void readZone2() {
  for (int i = 0; i < 3; i++) {
    readZone2Once();
    if (!isnan(zone2Temp)) return;
    delay(750);
  }
  zone2Temp = NAN;
}

void readAllSensors() {
  readSystemOnce();
  readZone1Once();
  readZone2Once();
}

void readAllSensorsBlocking() {
  readSystem();
  readZone1();
  readZone2();
}

void logSensorStatus() {
  Serial.print("System DHT GPIO");
  Serial.print(PIN_DHT_SYSTEM);
  Serial.print(" T=");
  Serial.print(isnan(systemTemp) ? -999 : systemTemp, 1);
  Serial.print(" H=");
  Serial.println(isnan(systemHum) ? -999 : systemHum, 0);

  Serial.print("Zone1 DHT GPIO");
  Serial.print(PIN_DHT_ZONE1);
  Serial.print(" T=");
  Serial.print(isnan(zone1Temp) ? -999 : zone1Temp, 1);
  Serial.print(" H=");
  Serial.println(isnan(zone1Hum) ? -999 : zone1Hum, 0);

  Serial.print("Zone2 DS18 GPIO");
  Serial.print(PIN_DS18B20);
  Serial.print(" T=");
  Serial.println(isnan(zone2Temp) ? -999 : zone2Temp, 1);
}

void initSensors() {
  pinMode(PIN_DHT_SYSTEM, INPUT);
  pinMode(PIN_DHT_ZONE1, INPUT);
  pinMode(PIN_DS18B20, INPUT);
  dhtSystem.begin();
  dhtZone1.begin();
  zone2Sensor.begin();
  zone2Sensor.setWaitForConversion(true);
}

void bleSend(String msg) {
  if (!phoneConnected || !bleTx) return;
  msg += "\n";
  bleTx->setValue(msg.c_str());
  bleTx->notify();
  Serial.println(">> " + msg);
}

void sendLiveToPhone() {
  if (!phoneConnected) return;
  if (!isnan(zone1Temp)) bleSend("TEMP1:" + String(zone1Temp, 1));
  if (!isnan(zone2Temp)) bleSend("TEMP2:" + String(zone2Temp, 1));
  if (!isnan(zone1Hum)) bleSend("HUM:" + String(zone1Hum, 0));
}

void handlePhoneCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;
  Serial.println("<< " + cmd);

  String up = cmd;
  up.toUpperCase();

  if (up == "PING" || up == "HELLO") {
    bleSend("OK:HELLO");
    return;
  }
  if (up == "TEMP_QUERY") {
    readAllSensorsBlocking();
    applyTempControl();
    sendLiveToPhone();
    if (screenStep >= 3) showRunning();
    return;
  }
  if (up.startsWith("PIN_UNLOCK:")) {
    handlePinUnlockBle(cmd.substring(11));
    return;
  }
  if (up.startsWith("TEMP_SET:")) {
    handleTempSet(cmd);
    return;
  }
  if (up.startsWith("SERVO_OPEN:")) {
    handleServoOpenBle(cmd.substring(11));
    return;
  }
  if (up.startsWith("SERVO_CLOSE:")) {
    handleServoCloseBle(cmd.substring(12));
    return;
  }
  if (up == "SERVO_ALL_CLOSE") {
    handleServoAllCloseBle();
    return;
  }
  bleSend("OK:ECHO:" + cmd);
}

void onBleData(const uint8_t *data, size_t len) {
  for (size_t i = 0; i < len; i++) {
    char c = (char)data[i];
    if (c == '\n' || c == '\r') {
      if (bleLine.length() > 0) {
        handlePhoneCommand(bleLine);
        bleLine = "";
      }
    } else if (c >= 32 && c <= 126) {
      bleLine += c;
      if (bleLine.length() > 120) bleLine = "";
    }
  }
}

class PhoneConnectCb : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    phoneConnected = true;
    delay(50);
    bleSend("READY:CuraX-Box");
    if (screenStep >= 3) {
      readAllSensors();
      applyTempControl();
      sendLiveToPhone();
      showRunning();
    }
  }
  void onDisconnect(BLEServer *s) override {
    phoneConnected = false;
    BLEDevice::startAdvertising();
    if (screenStep >= 3) showRunning();
  }
};

class PhoneWriteCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    if (v.length() > 0) onBleData((const uint8_t *)v.c_str(), v.length());
  }
};

void startBle() {
  BLEDevice::init(bleName);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new PhoneConnectCb());

  BLEService *service = server->createService(bleService);

  BLECharacteristic *rx = service->createCharacteristic(
      bleRxUuid, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new PhoneWriteCb());

  bleTx = service->createCharacteristic(bleTxUuid, BLECharacteristic::PROPERTY_NOTIFY);
  bleTx->addDescriptor(new BLE2902());

  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(bleService);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

void initKeypad() {
  keypad.setDebounceTime(8);
  for (byte i = 0; i < 4; i++) {
    pinMode(rowPins[i], INPUT_PULLUP);
    pinMode(colPins[i], INPUT_PULLUP);
  }
}

void trySubmitPin() {
  submitPinValue(typedPin, false);
}

bool submitPinValue(const String &pin, bool fromBle) {
  if (pin == correctPin) {
    typedPin = "";
    if (fromBle) {
      screenStep = 3;
      lastSensorMs = 0;
      readAllSensors();
      applyTempControl();
      showRunning();
      bleSend("OK:UNLOCK");
      Serial.println("BLE PIN OK -> Running");
    } else {
      screenStep = 2;
      stepTime = millis();
      showCenter("System Started");
    }
    return true;
  }

  typedPin = "";
  if (fromBle) {
    bleSend("ERR:PIN_WRONG");
    Serial.println("BLE PIN wrong");
  } else {
    showCenter("Wrong PIN", "Try again");
    delay(800);
    showPinScreen();
  }
  return false;
}

void handlePinUnlockBle(const String &pinRaw) {
  String pin = "";
  for (unsigned int i = 0; i < pinRaw.length(); i++) {
    if (isDigit(pinRaw.charAt(i))) pin += pinRaw.charAt(i);
  }
  if (pin.length() == 0) {
    bleSend("ERR:PIN_FORMAT");
    return;
  }
  if (screenStep >= 3) {
    bleSend("OK:UNLOCK:ALREADY");
    return;
  }
  if (screenStep == 0) {
    screenStep = 1;
  }
  submitPinValue(pin, true);
}

void handlePinKey(char key) {
  if (key >= '0' && key <= '9') {
    if (typedPin.length() < 8) typedPin += key;
    showPinScreen();
    return;
  }

  if (key == '*') {
    trySubmitPin();
    return;
  }

  if (key == '#') {
    typedPin = "";
    showPinScreen();
  }
}

void handleSerialPin() {
  if (screenStep >= 2 || !Serial.available()) return;

  while (Serial.available()) {
    char c = Serial.read();
    if (c >= '0' && c <= '9') {
      if (screenStep == 0) {
        screenStep = 1;
      }
      if (typedPin.length() < 8) {
        typedPin += c;
        showPinScreen();
        Serial.print("PIN typed: ");
        for (unsigned int i = 0; i < typedPin.length(); i++) Serial.print('*');
        Serial.println();
      }
    } else if (c == '*' || c == '\n' || c == '\r') {
      if (screenStep == 0) {
        screenStep = 1;
      }
      if (typedPin.length() > 0) {
        trySubmitPin();
      }
    } else if (c == '#') {
      typedPin = "";
      showPinScreen();
      Serial.println("PIN cleared");
    }
  }
}

void handleKeypad() {
  char key = keypad.getKey();
  if (!key) return;
  if (screenStep == 1) handlePinKey(key);
}

void handleSerialControl() {
  if (screenStep < 3 || !Serial.available()) return;

  char c = Serial.read();
  while (Serial.available()) Serial.read();

  if (c >= '1' && c <= '4') {
    if (c == '1') setFans(true);
    else if (c == '2') setFans(false);
    else if (c == '3') setCooler(true);
    else if (c == '4') setCooler(false);
    showRunning();
    return;
  }
  if (c == '5') {
    drawerOpen(0);
    showRunning();
    return;
  }
  if (c == '6') {
    drawerClose(0);
    showRunning();
    return;
  }
  if (c == '0') {
    drawerCloseActive();
    showRunning();
    return;
  }

  int ch = letterToCh(c);
  if (ch >= 0) {
    drawerOpen(ch);
    showRunning();
    return;
  }
  if (c == 's' || c == 'S') scanPcaBus18_19();
}

void updateStartupScreens() {
  unsigned long now = millis();

  if (screenStep == 0 && now - stepTime >= 2500) {
    screenStep = 1;
    showPinScreen();
  }

  if (screenStep == 2 && now - stepTime >= 2500) {
    screenStep = 3;
    lastSensorMs = 0;
    readAllSensors();
    showRunning();
  }
}

void handleSerialDiag() {
  if (!Serial.available()) return;
  char peek = Serial.peek();
  if (peek != '?' && peek != 'R' && peek != 'r' && peek != 'I' && peek != 'i' &&
      peek != 'L' && peek != 'l') return;

  char c = Serial.read();
  while (Serial.available()) Serial.read();

  if (c == 'R' || c == 'r') {
    Serial.println("ESP32 reboot...");
    delay(150);
    ESP.restart();
  }
  if (c == 'L' || c == 'l') {
    recoverDisplays();
  }
  if (c == '?' || c == 'I' || c == 'i') {
    scanI2cBus();
    logSensorStatus();
    Serial.print("screenStep=");
    Serial.print(screenStep);
    Serial.print(" OLED=");
    Serial.print(oledOk ? "OK" : "NO");
    Serial.print(" LCD=");
    Serial.println(lcdOk ? "OK" : "NO");
  }
}

void setup() {
  Serial.begin(115200);
  releaseStuckPins();
  delay(500);

  resetBootState();

  initI2cBus();
  reportI2cPinLevels();
  initDisplays();
  warnIfPcaOnDisplayBus();

  initRelays();
  initKeypad();
  initSensors();

  startOpeningScreen();

  Serial.println("=== CuraX boot (upload/EN reset) ===");
  Serial.println("Flow: Opening -> PIN -> Started -> Running");
  Serial.println("PIN: keypad 147+*  |  Serial: 147+* or Enter");
  Serial.println("Drawers SERIAL: a-f open | 0 band | 5/6 CH0 | 1-4 fans/cooler");
  Serial.println("PCA bus: GPIO 18/19 ONLY (21/22 = OLED+LCD only)");
  Serial.println("Serial: ? = scan | R = reboot | L = LCD/OLED recover");
  scanI2cBootCheck();
  Serial.print("OLED=");
  Serial.print(oledOk ? "OK" : "NO");
  Serial.print("  LCD=");
  Serial.println(lcdOk ? "OK" : "NO");

  startBle();
}

void loop() {
  handleSerialDiag();
  handleSerialPin();
  updateStartupScreens();
  handleKeypad();
  handleSerialControl();

  if (screenStep == 3 && millis() - lastSensorMs > 2000) {
    lastSensorMs = millis();
    readAllSensors();
    applyTempControl();
    showRunning();
    sendLiveToPhone();
  }

  delay(10);
}
