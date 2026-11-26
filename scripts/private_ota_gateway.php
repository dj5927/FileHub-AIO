<?php
declare(strict_types=1);
// Private files are deliberately outside the web document root.
ini_set('display_errors', '0');
header('Cache-Control: private, no-store');
header('X-Content-Type-Options: nosniff');
function deny(int $code): void { http_response_code($code); exit; }
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET' && ($_SERVER['REQUEST_METHOD'] ?? '') !== 'HEAD') deny(405);
$root = __DIR__ . '/../../../../dogpan_private/filehub_ota';
$expected = @file_get_contents($root . '/access.sha256');
$token = $_SERVER['HTTP_X_FILEHUB_ACCESS'] ?? '';
if (!is_string($token) || !preg_match('/^[A-Za-z0-9_-]{32,128}$/D', $token) ||
    $expected === false || !hash_equals(trim($expected), hash('sha256', $token))) deny(403);
$asset = $_GET['asset'] ?? '';
if (!is_string($asset)) deny(404);
$raw = @file_get_contents($root . '/manifest.json', false, null, 0, 250001);
if ($raw === false || strlen($raw) > 250000) deny(503);
$m = json_decode($raw, true);
if (!is_array($m) || ($m['channel'] ?? '') !== 'direct' || ($m['package'] ?? '') !== 'com.nasmount.mobile' ||
    !preg_match('/^[0-9]+[.][0-9]+[.][0-9]+$/D', $m['versionName'] ?? '')) deny(503);
$v = $m['versionName'];
$apk = 'FileHub-AIO-v' . $v . '.apk';
$source = 'FileHub-AIO-Direct-v' . $v . '-source.zip';
if (($m['filename'] ?? '') !== $apk || ($m['sourceFilename'] ?? '') !== $source ||
    !is_file($root . '/' . $apk) || !is_file($root . '/' . $source) ||
    filesize($root . '/' . $apk) !== ($m['size'] ?? -1)) deny(503);
if ($asset === 'manifest') {
    header('Content-Type: application/json');
    header('Content-Length: ' . strlen($raw));
    if ($_SERVER['REQUEST_METHOD'] !== 'HEAD') echo $raw;
    exit;
}
if ($asset !== $apk && $asset !== $source) deny(404);
$file = $root . '/' . $asset;
if (is_link($file)) deny(503);
$fp = @fopen($file, 'rb');
if ($fp === false) deny(503);
header('Content-Type: application/octet-stream');
header('Content-Disposition: attachment; filename="' . $asset . '"');
header('Content-Length: ' . filesize($file));
if ($_SERVER['REQUEST_METHOD'] !== 'HEAD') {
    @set_time_limit(0);
    while (ob_get_level() > 0) ob_end_clean();
    fpassthru($fp);
}
fclose($fp);
