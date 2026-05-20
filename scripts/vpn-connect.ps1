#
# Conecta la VPN FortiClient usando el perfil "IFX - MUEVETE BI".
#
# Estrategia: SIEMPRE empezar de cero (kill total + restart servicios + relaunch UI).
# Esto es ~30s mas lento que reusar pero es DEFINITIVAMENTE confiable. FortiClient
# se cuelga lo suficientemente seguido como para que esta sea la unica forma de
# garantizar que la conexion va a funcionar.
#
# Asume:
#   - FortiClient instalado en C:\Program Files\Fortinet\FortiClient\
#   - Perfil "IFX - MUEVETE BI" creado con "Guardar Contrasena" habilitado
#   - Sesion interactiva activa (Administrador logueado por RDP, no desconectado)
#
# Programar con Task Scheduler a las 17:50 con flag /IT (interactive task).
#

$ErrorActionPreference = "Continue"
$logFile = "C:\muevete-bi-etl\logs\vpn-connect.log"

function Write-Log($msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "$ts  $msg" | Out-File -FilePath $logFile -Append -Encoding utf8
}

function Test-VpnConnected {
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect("136.20.0.52", 5432, $null, $null)
        $ok  = $iar.AsyncWaitHandle.WaitOne(5000, $false)
        if ($ok) { $tcp.EndConnect($iar); $tcp.Close(); return $true }
        $tcp.Close(); return $false
    } catch { return $false }
}

# Win32 helpers para enumerar ventanas, traer al frente y simular input.
Add-Type @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public class Win32 {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc enumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet=CharSet.Auto)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();

    public static List<KeyValuePair<IntPtr,string>> FindWindowsByProcessIds(uint[] pids) {
        var result = new List<KeyValuePair<IntPtr,string>>();
        var set = new HashSet<uint>(pids);
        EnumWindows((hWnd, lParam) => {
            uint pid;
            GetWindowThreadProcessId(hWnd, out pid);
            if (set.Contains(pid)) {
                int len = GetWindowTextLength(hWnd);
                var sb = new StringBuilder(len + 1);
                GetWindowText(hWnd, sb, sb.Capacity);
                result.Add(new KeyValuePair<IntPtr,string>(hWnd, sb.ToString()));
            }
            return true;
        }, IntPtr.Zero);
        return result;
    }

    public static List<KeyValuePair<IntPtr,string>> FindVisibleWindowsByTitle(string[] substrings) {
        var result = new List<KeyValuePair<IntPtr,string>>();
        EnumWindows((hWnd, lParam) => {
            if (!IsWindowVisible(hWnd)) return true;
            int len = GetWindowTextLength(hWnd);
            if (len == 0) return true;
            var sb = new StringBuilder(len + 1);
            GetWindowText(hWnd, sb, sb.Capacity);
            string title = sb.ToString();
            foreach (var s in substrings) {
                if (title.IndexOf(s, StringComparison.OrdinalIgnoreCase) >= 0) {
                    result.Add(new KeyValuePair<IntPtr,string>(hWnd, title));
                    break;
                }
            }
            return true;
        }, IntPtr.Zero);
        return result;
    }
}
"@

# Mata UI + daemons + servicios de FortiClient. Es agresivo pero es la unica forma
# de garantizar que vamos a partir de un estado limpio. Cuando FortiClient esta
# colgado a veces ni manualmente abre hasta no hacer esto.
function Reset-FortiClientCompletely {
    Write-Log "--- Reset total de FortiClient ---"

    # Pasada 1: matar UI explicita
    $ui = Get-Process -Name "FortiClient" -ErrorAction SilentlyContinue
    if ($ui) {
        Write-Log "Matando $($ui.Count) instancia(s) de FortiClient.exe (UI)..."
        $ui | Stop-Process -Force -ErrorAction SilentlyContinue
    }

    # Pasada 2: detener TODOS los servicios Forti
    $services = Get-Service | Where-Object { $_.Name -like "*Forti*" }
    if ($services) {
        foreach ($s in $services) {
            if ($s.Status -ne "Stopped") {
                Write-Log "Deteniendo servicio $($s.Name) (estado=$($s.Status))..."
                Stop-Service -Name $s.Name -Force -ErrorAction SilentlyContinue
            }
        }
    }

    # Pasada 3: matar cualquier proceso Forti residual (daemons huerfanos)
    Start-Sleep -Seconds 2
    $remaining = Get-Process | Where-Object { $_.ProcessName -like "*Forti*" }
    if ($remaining) {
        Write-Log "Matando procesos residuales: $($remaining.ProcessName -join ', ')"
        $remaining | Stop-Process -Force -ErrorAction SilentlyContinue
    }

    Start-Sleep -Seconds 5

    # Verificar que quedo limpio
    $stillThere = Get-Process | Where-Object { $_.ProcessName -like "*Forti*" }
    if ($stillThere) {
        Write-Log "ADVERTENCIA: aun quedan $($stillThere.Count) proceso(s) Forti tras reset, segundo intento..."
        $stillThere | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 3
    } else {
        Write-Log "Reset completo - no quedan procesos Forti."
    }

    # Reiniciar servicios marcados como Automatic
    $autoServices = Get-Service | Where-Object { $_.Name -like "*Forti*" -and $_.StartType -eq "Automatic" }
    foreach ($s in $autoServices) {
        Write-Log "Iniciando servicio $($s.Name)..."
        Start-Service -Name $s.Name -ErrorAction SilentlyContinue
    }

    # Esperar a que los daemons esten listos
    Start-Sleep -Seconds 8

    # Lanzar la UI con working dir correcto
    Write-Log "Lanzando FortiClient.exe (working dir = C:\Program Files\Fortinet\FortiClient)..."
    Start-Process -FilePath "C:\Program Files\Fortinet\FortiClient\FortiClient.exe" `
                  -WorkingDirectory "C:\Program Files\Fortinet\FortiClient"

    # 20s en cold start: la UI Electron tarda en estar lista para recibir input
    Start-Sleep -Seconds 20
    Write-Log "--- Reset terminado ---"
}

function Focus-FortiClient {
    $procs = Get-Process -Name "FortiClient" -ErrorAction SilentlyContinue
    if (-not $procs) { return $false }
    [uint32[]]$pids = $procs | ForEach-Object { [uint32]$_.Id }

    $windows = [Win32]::FindWindowsByProcessIds($pids)
    if (-not $windows -or $windows.Count -eq 0) {
        Write-Log "No se encontraron ventanas top-level para FortiClient (pids=$($pids -join ','))."
        return $false
    }

    foreach ($w in $windows) {
        Write-Log "FortiClient hwnd=$($w.Key) title='$($w.Value)'"
    }

    $target = $windows | Where-Object { $_.Value -match "FortiClient" } | Select-Object -First 1
    if (-not $target) { $target = $windows | Where-Object { $_.Value -and $_.Value.Trim() -ne "" } | Select-Object -First 1 }
    if (-not $target) { $target = $windows | Select-Object -First 1 }

    $hwnd = $target.Key
    Write-Log "Restaurando ventana hwnd=$hwnd title='$($target.Value)'"
    [Win32]::ShowWindowAsync($hwnd, 9) | Out-Null  # SW_RESTORE
    Start-Sleep -Milliseconds 400
    [Win32]::ShowWindow($hwnd, 5) | Out-Null       # SW_SHOW
    Start-Sleep -Milliseconds 200

    # Truco anti-foreground-lock: simular Alt cuenta como input legitimo y desbloquea
    # SetForegroundWindow cuando el script corre via Task Scheduler.
    [Win32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)        # Alt down
    Start-Sleep -Milliseconds 50
    [Win32]::keybd_event(0x12, 0, 0x0002, [UIntPtr]::Zero)   # Alt up
    Start-Sleep -Milliseconds 100

    [Win32]::SetForegroundWindow($hwnd) | Out-Null
    Start-Sleep -Milliseconds 600

    $fg = [Win32]::GetForegroundWindow()
    if ($fg -ne $hwnd) {
        Write-Log "ADVERTENCIA: foreground real=$fg, esperado=$hwnd. Reintentando focus..."
        Start-Sleep -Milliseconds 500
        [Win32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 50
        [Win32]::keybd_event(0x12, 0, 0x0002, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 100
        [Win32]::SetForegroundWindow($hwnd) | Out-Null
        Start-Sleep -Milliseconds 600
        $fg = [Win32]::GetForegroundWindow()
        if ($fg -ne $hwnd) {
            Write-Log "ERROR: no se logro poner FortiClient en foreground (foreground=$fg). Las teclas pueden no llegar."
        }
    }
    return $true
}

# Patrones de titulo para detectar el modal del certificado del servidor.
# Cubrimos ingles y variantes en espanol porque depende de version y locale.
$certTitles = @(
    "Certificate Warning",
    "Server Certificate",
    "Server Certificate Warning",
    "Confirmation",
    "Confirmation Required",
    "Certificado",
    "Security Alert"
    "Advertencia de certificado",
    "Verificar certificado",
    "Aviso de certificado"
)

# Enfoca FortiClient, manda TAB x6 + ENTER, y espera hasta $waitSeconds segundos a
# que la VPN suba mientras vigila el modal del cert. Devuelve $true si conecto.
function Try-ConnectOnce {
    param([int]$waitSeconds = 30, [string]$attemptLabel = "1")

    if (-not (Focus-FortiClient)) {
        Write-Log "Intento ${attemptLabel}: no se pudo enfocar FortiClient."
        return $false
    }

    $keys = "{TAB}{TAB}{TAB}{TAB}{TAB}{TAB}{ENTER}"
    Write-Log "Intento ${attemptLabel}: enviando teclas $keys"
    [System.Windows.Forms.SendKeys]::SendWait($keys)

    $certHandled = $false
    $iterations = [int]($waitSeconds / 2)
    for ($i = 1; $i -le $iterations; $i++) {
        Start-Sleep -Seconds 2

        # El modal del cert puede aparecer en cualquier momento durante la espera
        if (-not $certHandled) {
            $certs = [Win32]::FindVisibleWindowsByTitle($certTitles)
            if ($certs -and $certs.Count -gt 0) {
                $certWnd = $certs[0]
                Write-Log "Dialogo certificado detectado: hwnd=$($certWnd.Key) title='$($certWnd.Value)'. Aceptando..."
                # Mismo truco anti-foreground para el cert
                [Win32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
                Start-Sleep -Milliseconds 50
                [Win32]::keybd_event(0x12, 0, 0x0002, [UIntPtr]::Zero)
                Start-Sleep -Milliseconds 100
                [Win32]::SetForegroundWindow($certWnd.Key) | Out-Null
                Start-Sleep -Milliseconds 300
                [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
                $certHandled = $true
            }
        }

        if (Test-VpnConnected) {
            $certNote = if ($certHandled) { "cert aceptado" } else { "sin cert (cacheado)" }
            Write-Log "VPN conectada tras $($i*2)s en intento ${attemptLabel} ($certNote)."
            return $true
        }
    }

    Write-Log "Intento ${attemptLabel}: VPN no respondio tras $waitSeconds s."
    return $false
}

# ============================================================
# FLUJO PRINCIPAL
# ============================================================

Write-Log "=== Chequeo VPN ==="

if (Test-VpnConnected) {
    Write-Log "VPN ya conectada (136.20.0.52:5432 alcanzable). No se hace nada."
    exit 0
}

Write-Log "VPN no responde. Iniciando reconexion completa..."

Add-Type -AssemblyName System.Windows.Forms

# SIEMPRE empezar de cero. Es la unica forma de evitar el caso "FortiClient colgado".
Reset-FortiClientCompletely

# Hasta 3 intentos de conectar. Si la UI fria deja el foco en un estado raro, el
# reintento con Focus-FortiClient + Alt + SetForegroundWindow suele recuperarlo.
$connected = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    if (Try-ConnectOnce -waitSeconds 30 -attemptLabel "$attempt") {
        $connected = $true
        break
    }
}

if ($connected) { exit 0 }

Write-Log "ERROR: VPN no conecto tras reset completo + 3 intentos. Revisar FortiClient manualmente."
exit 1
