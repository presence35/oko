<?php
/**
 * Oko beta log drop box.
 *
 * The app POSTs one JSON bundle per human tap to this endpoint. We write it into a directory
 * OUTSIDE the web root, so a tester's device model, prefs and log history are never publicly
 * reachable at a guessable URL — you read these over the same FTP account `uploadRelease`
 * already uses, and nothing else can.
 *
 * Setup
 *   1. Edit LOG_TOKEN below to a long random string.
 *   2. Paste the same string into UPLOAD_TOKEN in app/src/main/java/com/odesaplay/oko/service/LogUpload.kt
 *   3. Upload this file to other_apps/oko/upload.php
 *   4. mkdir other_apps/oko_logs  (chmod 700 if the host allows)
 *
 * Bundles land at other_apps/oko_logs/<brand>_<model>_<versionCode>.json and are overwritten in
 * place: one slot per device per build, always the freshest state, nothing to clean up.
 */

const LOG_TOKEN = 'change-me';
const LOG_DIR = __DIR__ . '/../oko_logs';   // outside the web root on purpose
const MAX_BYTES = 8 * 1024 * 1024;

header('Content-Type: application/json');

function fail(string $why, int $code): void {
    http_response_code($code);
    echo json_encode(['ok' => false, 'error' => $why]);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    fail('POST only', 405);
}

$sent = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
if (!hash_equals('Bearer ' . LOG_TOKEN, $sent)) {
    fail('bad token', 401);
}

$body = file_get_contents('php://input');
if ($body === false || $body === '') {
    fail('empty body', 400);
}
if (strlen($body) > MAX_BYTES) {
    fail('too large', 413);
}

$decoded = json_decode($body, true);
if (!is_array($decoded) || !isset($decoded['schema'])) {
    fail('not a log bundle', 400);
}

// Never trust the client with a path. Rebuild the name from fields we validate ourselves,
// falling back to the header, and keep it to a flat safe charset with no separators.
$name = preg_replace('/[^A-Za-z0-9._-]/', '', (string) ($_SERVER['HTTP_X_LOG_NAME'] ?? ''));
if ($name === '' || !str_ends_with($name, '.json')) {
    $name = 'unknown.json';
}

if (!is_dir(LOG_DIR) && !@mkdir(LOG_DIR, 0700, true) && !is_dir(LOG_DIR)) {
    fail('storage unavailable', 500);
}

$target = LOG_DIR . '/' . $name;
$tmp = $target . '.part';
if (file_put_contents($tmp, $body, LOCK_EX) === false || !rename($tmp, $target)) {
    @unlink($tmp);
    fail('write failed', 500);
}
@chmod($target, 0600);

echo json_encode(['ok' => true, 'name' => $name, 'bytes' => strlen($body)]);
