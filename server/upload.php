<?php
/**
 * Oko beta log drop box.
 *
 * The app POSTs one JSON bundle per human tap to this endpoint. We write it into a directory
 * OUTSIDE the web root, so a tester's device model, prefs and log history are never publicly
 * reachable at a guessable URL — you read these over the same FTP account `uploadRelease`
 * already uses, and nothing else can.
 *
 * Setup: upload this file to other_apps/oko/upload.php. Nothing else — the log directory is
 * created on first upload, and the app ships the filename in an X-Log-Name header that we
 * sanitize rather than trust.
 *
 * NO SHARED SECRET, ON PURPOSE. A token baked into the APK is extractable by anyone who unpacks
 * it, so it guards nothing while adding a setup step to every beta tester. What replaces it is
 * the part that actually limits abuse without a secret:
 *   - per-IP rate limit (REMOTE_ADDR, never X-Forwarded-For, which is trivially spoofed)
 *   - a hard body cap and a JSON-shape check, so it cannot be used as arbitrary file upload
 *   - a filename forced to a flat sanitized .json inside one fixed directory
 *   - an age-based prune of the directory, so total disk use is bounded without ever touching
 *     a recent report
 *
 * Bundles land at other_apps/oko_logs/<brand>_<model>_<versionCode>.json and are overwritten in
 * place: one slot per device per build, always the freshest state, nothing to clean up.
 */

const LOG_DIR = __DIR__ . '/../oko_logs';   // outside the web root on purpose
const MAX_BYTES = 8 * 1024 * 1024;

// Per-IP budget. A tester tapping a few times an hour never notices these; a script filling the
// directory hits 429 and stops. Generous enough not to break a frustrated double-tap.
const BURST_SEC = 30;
const PER_HOUR = 20;

// Delete bundles older than this. 0 disables pruning (manual retention).
const KEEP_DAYS = 90;

header('Content-Type: application/json');
header('Cache-Control: no-store');

function fail(string $why, int $code): void {
    http_response_code($code);
    // Never surface a PHP notice/warning: a 500 with a path in it tells a prober the layout.
    echo json_encode(['ok' => false, 'error' => $why]);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    header('Allow: POST');
    fail('POST only', 405);
}

/**
 * Per-IP sliding window, kept in the system temp dir (writable on shared hosting, and not
 * reachable over HTTP). Returns the number of writes still allowed in the burst window.
 * Keyed on REMOTE_ADDR: X-Forwarded-For is attacker-controlled, so trusting it would let a
 * single client reset its own counter by sending a random header.
 */
function rate_ok(string $file, int $now): bool {
    $window = $now - 3600;
    $stamps = [];
    if (is_readable($file)) {
        $raw = @file_get_contents($file);
        if (is_string($raw)) {
            $decoded = json_decode($raw, true);
            if (is_array($decoded)) {
                foreach ($decoded as $t) {
                    if (is_int($t) && $t > $window) $stamps[] = $t;
                }
            }
        }
    }
    if (count($stamps) >= PER_HOUR) return false;
    if ($stamps !== [] && ($now - max($stamps)) < BURST_SEC) return false;

    $stamps[] = $now;
    // Fail CLOSED if the counter cannot be persisted: a silently-unwritable temp dir would
    // leave the endpoint effectively unthrottled, which is the one outcome worse than an error.
    return @file_put_contents($file, json_encode($stamps), LOCK_EX) !== false;
}

try {
    if (!is_writable(sys_get_temp_dir())) {
        error_log('upload.php: rate-limit dir not writable');
        fail('rate limiting unavailable', 503);
    }

    $ip = $_SERVER['REMOTE_ADDR'] ?? 'unknown';
    $now = time();
    $slot = rtrim(sys_get_temp_dir(), '/') . '/oko_logs_rate_' . substr(sha1((string) $ip), 0, 16) . '.json';
    if (!rate_ok($slot, $now)) {
        header('Retry-After: ' . BURST_SEC);
        fail('too many uploads from this address', 429);
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

    // Never trust the client with a path. Rebuild the name from a field we validate ourselves,
    // keeping it to a flat safe charset with no separators.
    $name = preg_replace('/[^A-Za-z0-9._-]/', '', (string) ($_SERVER['HTTP_X_LOG_NAME'] ?? ''));
    if ($name === '' || !str_ends_with($name, '.json')) {
        $name = 'unknown.json';
    }

    if (!is_dir(LOG_DIR) && !@mkdir(LOG_DIR, 0700, true) && !is_dir(LOG_DIR)) {
        fail('storage unavailable', 500);
    }

    // Bound total disk use without ever touching a recent report. Rate limiting stops one
    // client; this stops a client that rotates its address.
    if (KEEP_DAYS > 0) {
        $cutoff = $now - (KEEP_DAYS * 86400);
        foreach ((array) @glob(LOG_DIR . '/*.json') as $old) {
            // filemtime() returns false on failure, and false coerces to 0 — which is < $cutoff,
            // so a bare comparison would delete exactly the files it cannot stat. Never.
            $mt = @filemtime($old);
            if (is_int($mt) && $mt < $cutoff) @unlink($old);
        }
    }

    // Write to a temp name and rename: rename is atomic within a filesystem, so a reader (or
    // the FTP client you read these with) never sees a half-written bundle.
    $target = LOG_DIR . '/' . $name;
    $tmp = $target . '.part';
    if (file_put_contents($tmp, $body, LOCK_EX) === false || !rename($tmp, $target)) {
        @unlink($tmp);
        fail('write failed', 500);
    }
    @chmod($target, 0600);

    echo json_encode(['ok' => true, 'name' => $name, 'bytes' => strlen($body)]);
} catch (Throwable $e) {
    error_log('upload.php: ' . $e->getMessage());
    fail('server error', 500);
}
