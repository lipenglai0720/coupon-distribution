param(
  [Parameter(Mandatory)][string]$JMeter,
  [string]$Accounts = '.local/accounts.json',
  [string]$Merchant = 'merchant',
  [string]$Customer = 'customer',
  [string]$Origin = 'http://127.0.0.1:8080',
  [int]$Threads = 32,
  [int]$Loops = 2500
)
$ErrorActionPreference = 'Stop'
$uri = [Uri]$Origin
if ($uri.Scheme -ne 'http' -or !$uri.IsLoopback) { throw 'Run against the local test deployment.' }
$seeds = Get-Content -LiteralPath $Accounts -Raw | ConvertFrom-Json

function Invoke-Coupon($Route, $Session, $Method, $Data) {
  $request = @{Uri="$Origin/api$Route"; Method=$Method}
  if ($Session) { $request.Headers = @{Authorization=('Bearer ' + $Session.token)} }
  if ($null -ne $Data) {
    $request.ContentType = 'application/json'
    $request.Body = $Data | ConvertTo-Json -Compress
  }
  Invoke-RestMethod @request
}

function Login-Coupon($Username) {
  $seed = $seeds | Where-Object username -eq $Username
  if (!$seed) { throw "Missing fixture account $Username" }
  Invoke-Coupon '/auth/login' $null Post @{username=$seed.username;password=$seed.password}
}

$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$folder = New-Item -ItemType Directory -Path ".local/settlement-auth-$stamp"
$authFile = Join-Path $folder.FullName 'temporary-jmeter.properties'
$seller = $null
$buyer = $null
try {
  $seller = Login-Coupon $Merchant
  $buyer = Login-Coupon $Customer
  if ($seller.identity.subjectId -ne 1) { throw 'Use a merchant fixture with subjectId 1 for this JMeter plan.' }
  $template = Invoke-Coupon '/templates' $seller Post @{
    merchantId=1;name='结算压测 · 满100减20';stock=1;thresholdCents=10000
    discountCents=2000;validFrom=$stamp-5000;validTo=$stamp+86400000
  }
  $null = Invoke-Coupon '/claims' $buyer Post @{templateId=$template.id;requestId="BENCH-$stamp"}
  for ($i = 0; $i -lt 30; $i++) {
    $eligible = Invoke-Coupon '/settlement?merchantId=1&amountCents=10000' $buyer Get $null
    if (!($eligible | Where-Object discount_cents -eq 2000)) { throw 'Missing eligible test coupon.' }
  }

  [IO.File]::WriteAllText($authFile,('token=' + $buyer.token),[Text.UTF8Encoding]::new($false))
  $env:HEAP = '-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m'
  $report = Join-Path $folder.FullName 'report'
  $jtl = Join-Path $folder.FullName 'samples.jtl'
  $log = Join-Path $folder.FullName 'jmeter.log'
  & $JMeter -n -t benchmarks/settlement.jmx -q $authFile "-Jhost=$($uri.Host)" "-Jport=$($uri.Port)" "-Jthreads=$Threads" "-Jloops=$Loops" -l $jtl -j $log -e -o $report
  if ($LASTEXITCODE -ne 0) { throw 'JMeter exited with an error.' }
  $result = (Get-Content -LiteralPath (Join-Path $report 'statistics.json') -Raw | ConvertFrom-Json).Total
  $result
  Write-Output "Report directory: $report"
  if ($result.sampleCount -ne $Threads * $Loops -or $result.errorCount -ne 0) { throw 'Settlement assertions failed.' }
} finally {
  if (Test-Path -LiteralPath $authFile) { Remove-Item -LiteralPath $authFile }
  if ($seller) { $null = Invoke-Coupon '/auth/logout' $seller Post $null }
  if ($buyer) { $null = Invoke-Coupon '/auth/logout' $buyer Post $null }
}
