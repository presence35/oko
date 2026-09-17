# One-time migration: replace every hardcoded color literal in shipping .kt files
# with `AppPalette` tokens. Not magic numbers. UTF-8-safe (.NET IO, BOM-preserving).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'app\src\main\java\ua\ukrainedrones'

$skip = @(
    'ThreatPalette.kt',
    'theme\DarkThemePlugin.kt',
    'flourish\ThreatDeathAnimation.kt',
    'ui\FeatureDiagrams.kt',
    'ui\MapLibreStyle.kt',
    'data\Shelters.kt'
)

# 0xRRGGBB (8 hex) --> token
$hexMap = @{
    'FFFFD500' = 'AlertYellow'; 'FFF9A825' = 'AlertYellow'; 'FFFFC107' = 'AlertYellow'; 'FFFFD54F' = 'AlertYellow'; 'FFFDD835' = 'AlertYellow'
    'FFD32F2F' = 'AlertRed'; 'FFE57373' = 'AlertRed'; 'FFB71C1C' = 'AlertRed'; 'FFD9737A' = 'AlertRed'; 'FFE53935' = 'AlertRed'
    'FF4CAF50' = 'SafeGreen'; 'FF43A047' = 'SafeGreen'; 'FF81C784' = 'SafeGreen'
    'FF005BBB' = 'UkraineBlue'; 'FF2196F3' = 'GpsBlue'; 'FF1E88E5' = 'WidgetBlue'; 'FF64B5F6' = 'Primary'
    'FFFB8C00' = 'DegradedOrange'; 'FFFFA000' = 'ShelterMobile'; 'FFE65100' = 'WarningOrange'; 'FFFFD700' = 'Gold'; 'FFFFB74D' = 'AreaOnlyDot'
    'FFE2E8F0' = 'CityTextDefault'
    'FF121212' = 'Background'; 'FF1A1A1A' = 'Surface'; 'FF232323' = 'SurfaceVariant'; 'FF1E1E1E' = 'Card'; 'FF1C1C1E' = 'Card'; 'FF1E2124' = 'Card'
    'FF1B1B1B' = 'CardDeep'; 'FF252525' = 'CardAlt'; 'FF151515' = 'Panel'; 'FF2A2A2A' = 'Chip'; 'FF2A2A2E' = 'Toast'; 'FF4A4A4E' = 'ToastBorder'
    'FFEDEDED' = 'TextPrimary'; 'FFE6E6E6' = 'TextPrimary'; 'FF9E9E9E' = 'TextSecondary'; 'FFB0B0B0' = 'TextTertiary'
    'FFCCCCCC' = 'TextDetail'; 'FFCFCFCF' = 'PillNumber'
    'FF3A3A3A' = 'Border'; 'FF555555' = 'BorderSoft'; 'FF666666' = 'BorderMuted'; 'FF777777' = 'IconDisabled'
    'FF888888' = 'HandleGrey'; 'FFB0BEC5' = 'GhostTick'; 'FF0D1117' = 'MapBackground'
    'FF1A1130' = 'NightSection'; 'FF44357A' = 'NightBorder'; 'FFFFF3E0' = 'WarningBg'; 'FF3A2B00' = 'WarningLine'; 'FF3A2E00' = 'StopPillBg'
}
# Color.argb(a,r,g,b) --> token
$argbMap = @{
    '230,13,17,23' = 'Mask'; '70,255,255,255' = 'LandBorder'; '120,180,180,200' = 'OblastBorder'; '70,180,180,200' = 'RaionBorder'
    '120,33,150,243' = 'GpsGlow'; '110,158,158,158' = 'GpsGlowOff'
}
# Color.rgb(r,g,b) --> token
$rgbMap = @{
    '0,91,187' = 'UkraineBlue'; '255,213,0' = 'AlertYellow'; '255,183,77' = 'AreaOnlyDot'; '33,150,243' = 'GpsBlue'
    '76,175,80' = 'SafeGreen'; '158,158,158' = 'TextSecondary'; '255,160,0' = 'ShelterMobile'
}
# Color.parseColor("#RRGGBB") --> token
$parseMap = @{ 'E53935' = 'AlertRed'; 'F9A825' = 'AlertYellow' }

function New-Utf8([bool]$withBom) { New-Object System.Text.UTF8Encoding($withBom) }

function Read-TextSafe([string]$path, [ref]$hadBom) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $hadBom.Value = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    $enc = New-Utf8 ($hadBom.Value)
    $s = $enc.GetString($bytes)
    if ($s.Length -gt 0 -and $s[0] -eq [char]0xFEFF) { return $s.Substring(1) }
    return $s
}

$files = Get-ChildItem -Path $src -Recurse -Filter *.kt | ForEach-Object { $_.FullName }
$unmapped = @()

foreach ($f in $files) {
    $rel = $f.Substring($src.Length + 1)
    $isSkip = $false
    foreach ($s in $skip) { if ($rel -ieq $s) { $isSkip = $true; break } }
    if ($isSkip) { continue }

    $hadBom = $false
    $text = Read-TextSafe $f ([ref]$hadBom)
    $orig = $text
    $count = 0

    # Pass 1: Compose Color(0xRRGGBB)
    $text = [regex]::Replace($text, '(?<pre>(?:androidx\.compose\.ui\.graphics\.)?Color)\((?<ws1>\s*)(?<hex>0x[0-9A-Fa-f]{8})(?<ws2>\s*)\)', {
        param($m)
        $key = $m.Groups['hex'].Value.ToUpper().Substring(2)
        if ($hexMap.ContainsKey($key)) {
            $script:count++
            return $m.Groups['pre'].Value + '(' + $m.Groups['ws1'].Value + 'AppPalette.' + $hexMap[$key] + $m.Groups['ws2'].Value + ')'
        }
        $script:unmapped += ($rel + ': Color(' + $m.Groups['hex'].Value + ')')
        return $m.Value
    })

    # Pass 2: raw 0xRRGGBB.toInt()
    $text = [regex]::Replace($text, '(?<hex>0x[0-9A-Fa-f]{8})\.toInt\(\)', {
        param($m)
        $key = $m.Groups['hex'].Value.ToUpper().Substring(2)
        if ($hexMap.ContainsKey($key)) {
            $script:count++
            return 'AppPalette.' + $hexMap[$key] + '.toInt()'
        }
        $script:unmapped += ($rel + ': ' + $m.Groups['hex'].Value + '.toInt()')
        return $m.Value
    })

    # Pass 3: android Color.rgb(r, g, b)
    $text = [regex]::Replace($text, 'Color\.rgb\(\s*(?<r>\d+)\s*,\s*(?<g>\d+)\s*,\s*(?<b>\d+)\s*\)', {
        param($m)
        $key = $m.Groups['r'].Value + ',' + $m.Groups['g'].Value + ',' + $m.Groups['b'].Value
        if ($rgbMap.ContainsKey($key)) {
            $script:count++
            return 'AppPalette.' + $rgbMap[$key] + '.toInt()'
        }
        $script:unmapped += ($rel + ': Color.rgb(' + $m.Groups['r'].Value + ',' + $m.Groups['g'].Value + ',' + $m.Groups['b'].Value + ')')
        return $m.Value
    })

    # Pass 4: android Color.argb(a, r, g, b)
    $text = [regex]::Replace($text, 'Color\.argb\(\s*(?<a>\d+)\s*,\s*(?<r>\d+)\s*,\s*(?<g>\d+)\s*,\s*(?<b>\d+)\s*\)', {
        param($m)
        $key = $m.Groups['a'].Value + ',' + $m.Groups['r'].Value + ',' + $m.Groups['g'].Value + ',' + $m.Groups['b'].Value
        if ($argbMap.ContainsKey($key)) {
            $script:count++
            return 'AppPalette.' + $argbMap[$key] + '.toInt()'
        }
        $script:unmapped += ($rel + ': Color.argb(' + $m.Groups['a'].Value + ',' + $m.Groups['r'].Value + ',' + $m.Groups['g'].Value + ',' + $m.Groups['b'].Value + ')')
        return $m.Value
    })

    # Pass 5: android Color.parseColor("#RRGGBB")
    $text = [regex]::Replace($text, 'Color\.parseColor\("#(?<hex>[0-9A-Fa-f]{6})"\)', {
        param($m)
        $key = $m.Groups['hex'].Value.ToUpper()
        if ($parseMap.ContainsKey($key)) {
            $script:count++
            return 'AppPalette.' + $parseMap[$key] + '.toInt()'
        }
        $script:unmapped += ($rel + ': parseColor(#' + $m.Groups['hex'].Value + ')')
        return $m.Value
    })

    # Identifier rename: ThreatPalette -> AppPalette, and relocate its import
    $text = $text.Replace('import ua.ukrainedrones.ThreatPalette', 'import ua.ukrainedrones.theme.AppPalette')
    $text = [regex]::Replace($text, '\bThreatPalette\b', { param($m) 'AppPalette' })

    # Import injection for consumers outside the theme package
    if ($text.Contains('AppPalette') -and -not $text.Contains('import ua.ukrainedrones.theme.AppPalette') -and $rel -notlike 'theme\*') {
        $text = [regex]::Replace($text, '(?m)^(package [^\r\n]+)\r?\n', { param($m)
            $m.Groups[1].Value + "`r`n" + 'import ua.ukrainedrones.theme.AppPalette' + "`r`n"
        }, 1)
    }

    if ($text -ne $orig) {
        $enc = New-Utf8 $hadBom
        [System.IO.File]::WriteAllText($f, $text, $enc)
        Write-Output ("{0,-72} {1,3} replacements" -f $rel, $count)
    }
}

Write-Output ''
if ($unmapped.Count -eq 0) { Write-Output 'No unmapped literals.' }
else {
    Write-Output 'UNMAPPED literals (review):'
    $unmapped | Sort-Object -Unique | ForEach-Object { Write-Output ('  ' + $_) }
}