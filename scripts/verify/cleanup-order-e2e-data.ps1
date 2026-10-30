# Remove only local E2E hotel fixtures from open-im-local.
#
# Default is a dry run. -Apply is explicit and refuses anything except the local
# namespace. This script never deletes customer bookings such as qian001.
param(
  [string]$Namespace = 'open-im-local',
  [switch]$Apply
)
$ErrorActionPreference = 'Stop'
if ($Namespace -ne 'open-im-local') { throw "Only open-im-local is allowed, got: $Namespace" }
$password = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(
  (kubectl -n $Namespace get secret open-im-env -o jsonpath='{.data.DB_PASSWORD}').Trim()))
$where = "guest_name LIKE 'E2E %' OR source = 'OTA_SANDBOX'"

function Sql([string]$query) {
  $out = & kubectl -n $Namespace exec deployment/mysql -- mysql -uroot "-p$password" open_saas -N -B -e $query
  if ($LASTEXITCODE -ne 0) { throw "SQL failed: $query" }
  return $out
}

$count = [int](Sql "SELECT COUNT(*) FROM htl_booking WHERE $where;")
Write-Host "E2E hotel bookings: $count"
if ($count -eq 0) { Write-Host 'Nothing to clean.'; exit 0 }

if (-not $Apply) {
  Sql "SELECT id, booking_no, guest_name, status, order_id FROM htl_booking WHERE $where ORDER BY id DESC LIMIT 20;"
  Write-Host 'Dry run only. Re-run with -Apply to delete these fixture rows.'
  exit 0
}

$bookingScope = "SELECT id FROM htl_booking WHERE $where"
$orderScope = "SELECT order_id FROM htl_booking WHERE $where AND order_id IS NOT NULL"
$folioScope = "SELECT id FROM htl_folio WHERE booking_id IN ($bookingScope)"
$sql = @"
START TRANSACTION;
DELETE FROM htl_folio_item WHERE folio_id IN ($folioScope);
DELETE FROM htl_folio WHERE booking_id IN ($bookingScope);
DELETE FROM htl_channel_booking WHERE booking_id IN ($bookingScope);
DELETE FROM htl_room_availability_hold WHERE booking_id IN ($bookingScope);
DELETE FROM htl_booking_night WHERE booking_id IN ($bookingScope);
DELETE FROM htl_housekeeping_task WHERE booking_id IN ($bookingScope);
DELETE FROM htl_refund WHERE booking_id IN ($bookingScope);
DELETE FROM htl_stay WHERE booking_id IN ($bookingScope);
DELETE FROM pay_refund WHERE order_id IN ($orderScope);
DELETE FROM pay_collect WHERE order_id IN ($orderScope);
DELETE FROM pay_transaction WHERE payment_intent_id IN (SELECT id FROM pay_intent WHERE order_id IN ($orderScope));
DELETE FROM pay_intent WHERE order_id IN ($orderScope);
DELETE FROM ord_order_item WHERE order_id IN ($orderScope);
DELETE FROM ord_order WHERE id IN ($orderScope);
DELETE FROM htl_booking WHERE $where;
COMMIT;
"@
Sql $sql | Out-Null
$left = [int](Sql "SELECT COUNT(*) FROM htl_booking WHERE $where;")
Write-Host "Cleanup complete; remaining E2E hotel bookings: $left"
if ($left -ne 0) { throw "Fixture cleanup left $left row(s)" }

