param([switch]$NoBrowser,[string]$VmHost=$env:COUPON_VM_HOST,[string]$VmUser=$env:COUPON_VM_USER,[string]$IdentityFile=$env:COUPON_VM_IDENTITY)
$ErrorActionPreference='Stop'
[System.Net.WebRequest]::DefaultWebProxy=$null
$projectRoot=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot=Join-Path $projectRoot '.local'

New-Item -ItemType Directory -Force -Path $localRoot | Out-Null
$log=Join-Path $localRoot 'start-development.log'
try {
  if(!$VmHost -or !$VmUser -or !$IdentityFile -or !(Test-Path -LiteralPath $IdentityFile)){throw '请配置 Ubuntu 地址、用户名和 SSH 密钥。'}
  $sshArgs=@('-i',$IdentityFile,'-o','IdentitiesOnly=yes','-o','BatchMode=yes','-o','ConnectTimeout=8',"${VmUser}@${VmHost}")
  & ssh.exe @sshArgs 'bash ~/quanshu-runtime/start-user-services.sh && bash ~/quanshu-runtime/start-nacos.sh && bash ~/quanshu-runtime/start-xxl-admin.sh && (curl --fail --silent http://127.0.0.1:8080/api/status > /dev/null || bash ~/quanshu-runtime/start-platform.sh)' *> $log
  if($LASTEXITCODE -ne 0){throw "Ubuntu 服务启动失败，请查看 $log。请先确认虚拟机已经开机。"}
  $forward=@()
  foreach($mapping in @(@(8080,8080),@(13306,3306),@(16379,6379),@(15673,5672),@(15672,15672),@(18080,18080),@(18848,18848))){
    if(!(Get-NetTCPConnection -LocalPort $mapping[0] -State Listen -ErrorAction SilentlyContinue)){
      $forward+=@('-L',"127.0.0.1:$($mapping[0]):127.0.0.1:$($mapping[1])")
    }
  }
  if($forward.Count -gt 0){
    $tunnelArgs=@('-N','-i',$IdentityFile,'-o','IdentitiesOnly=yes','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=30')+$forward+@("${VmUser}@${VmHost}")
    $process=Start-Process ssh.exe -ArgumentList $tunnelArgs -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $localRoot 'development-tunnel.log') -RedirectStandardOutput (Join-Path $localRoot 'development-tunnel.out.log')
    Set-Content -LiteralPath (Join-Path $localRoot 'development-tunnel.pid') -Value $process.Id
  }
  $ready=$false
  for($attempt=0;$attempt -lt 30;$attempt++){
    try{$status=Invoke-RestMethod 'http://127.0.0.1:8080/api/status' -TimeoutSec 3;if($status.serviceRole -eq 'coupon'){$ready=$true;break}}catch{}
    Start-Sleep -Milliseconds 500
  }
  if(!$ready){throw "本地转发未就绪，请查看 $localRoot 下的 tunnel 日志。"}
  if(!$NoBrowser){Start-Process 'http://localhost:8080'}
  Write-Output '优惠券分发与核销系统已就绪：http://localhost:8080'
}catch {
  Add-Content -LiteralPath $log -Value $_.Exception.Message
  if(!$NoBrowser){Add-Type -AssemblyName PresentationFramework;[System.Windows.MessageBox]::Show($_.Exception.Message,'优惠券分发与核销系统启动失败') | Out-Null}
  Write-Error -Message $_.Exception.Message -ErrorAction Continue
  exit 1
}
