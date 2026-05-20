#
# Convierte los documentos Markdown a PDF usando Microsoft Edge en modo headless.
# Funciona con PowerShell 5.1 o superior, no requiere instalacion de pandoc.
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File scripts\generate-docs-pdf.ps1
#

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path $PSScriptRoot -Parent
$docsDir = Join-Path $projectRoot "docs"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path $edge)) {
    $edge = "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
}
if (-not (Test-Path $edge)) {
    throw "No se encontro Microsoft Edge. Instalalo desde https://www.microsoft.com/edge"
}

# Convierte markdown a HTML con un parser simple (cubre headers, listas, tablas,
# code blocks, bold/italic, links, inline code). Suficiente para los docs del proyecto.
function ConvertTo-Html {
    param([string]$mdPath, [string]$title)

    $md = Get-Content -Path $mdPath -Raw

    # Code blocks (triple backtick) - reemplazar primero para no procesar markdown adentro
    $script:codeBlocks = @()
    $md = [regex]::Replace($md, '(?ms)```(\w*)\r?\n(.*?)\r?\n```', {
        param($m)
        $lang = $m.Groups[1].Value
        $code = $m.Groups[2].Value
        $code = $code -replace '&', '&amp;' -replace '<', '&lt;' -replace '>', '&gt;'
        $script:codeBlocks += "<pre><code class='lang-$lang'>$code</code></pre>"
        "@@CODEBLOCK_$($script:codeBlocks.Count - 1)@@"
    })

    # Inline code (single backtick)
    $md = [regex]::Replace($md, '`([^`\r\n]+)`', {
        param($m)
        $code = $m.Groups[1].Value
        $code = $code -replace '&', '&amp;' -replace '<', '&lt;' -replace '>', '&gt;'
        "<code>$code</code>"
    })

    # Tablas (formato markdown: | a | b |  con separador | --- | --- |)
    $md = [regex]::Replace($md, '(?m)^\|(.+)\|\s*$\r?\n^\|[\s:|-]+\|\s*$\r?\n((?:^\|.+\|\s*$\r?\n?)+)', {
        param($m)
        $header = $m.Groups[1].Value
        $body = $m.Groups[2].Value

        $headerCells = ($header -split '\|' | ForEach-Object { "<th>$($_.Trim())</th>" }) -join ''
        $bodyRows = ($body -split "`n" | Where-Object { $_.Trim() -ne '' } | ForEach-Object {
            $row = $_.Trim().TrimStart('|').TrimEnd('|')
            $cells = ($row -split '\|' | ForEach-Object { "<td>$($_.Trim())</td>" }) -join ''
            "<tr>$cells</tr>"
        }) -join ''
        "<table><thead><tr>$headerCells</tr></thead><tbody>$bodyRows</tbody></table>"
    })

    # Headers
    $md = [regex]::Replace($md, '(?m)^#### (.+)$', '<h4>$1</h4>')
    $md = [regex]::Replace($md, '(?m)^### (.+)$', '<h3>$1</h3>')
    $md = [regex]::Replace($md, '(?m)^## (.+)$', '<h2>$1</h2>')
    $md = [regex]::Replace($md, '(?m)^# (.+)$', '<h1>$1</h1>')

    # Horizontal rules
    $md = [regex]::Replace($md, '(?m)^---+\s*$', '<hr>')

    # Bold (**texto**)
    $md = [regex]::Replace($md, '\*\*([^*\r\n]+)\*\*', '<strong>$1</strong>')

    # Italic (*texto*)
    $md = [regex]::Replace($md, '(?<![*])\*([^*\r\n]+)\*(?![*])', '<em>$1</em>')

    # Links [text](url)
    $md = [regex]::Replace($md, '\[([^\]]+)\]\(([^)]+)\)', '<a href="$2">$1</a>')

    # Listas con guion o estrella
    $md = [regex]::Replace($md, '(?m)^(\s*)-\s+(.+)$', '<li>$2</li>')
    $md = [regex]::Replace($md, '(?m)^(\s*)\*\s+(.+)$', '<li>$2</li>')
    # Listas numeradas
    $md = [regex]::Replace($md, '(?m)^(\s*)\d+\.\s+(.+)$', '<li>$2</li>')
    # Envolver li consecutivos en ul
    $md = [regex]::Replace($md, '(?s)((?:<li>.*?</li>\s*)+)', '<ul>$1</ul>')

    # Blockquotes
    $md = [regex]::Replace($md, '(?m)^>\s*(.+)$', '<blockquote>$1</blockquote>')

    # Parrafos: doble newline = nuevo parrafo
    $lines = $md -split "`r?`n"
    $output = New-Object System.Text.StringBuilder
    $inParagraph = $false
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '') {
            if ($inParagraph) {
                $null = $output.Append("</p>`n")
                $inParagraph = $false
            }
        } elseif ($trimmed -match '^<(h[1-6]|ul|ol|li|table|thead|tbody|tr|th|td|pre|hr|blockquote|p)') {
            if ($inParagraph) {
                $null = $output.Append("</p>`n")
                $inParagraph = $false
            }
            $null = $output.Append($line + "`n")
        } elseif ($trimmed -match '^@@CODEBLOCK_') {
            if ($inParagraph) {
                $null = $output.Append("</p>`n")
                $inParagraph = $false
            }
            $null = $output.Append($line + "`n")
        } else {
            if (-not $inParagraph) {
                $null = $output.Append("<p>")
                $inParagraph = $true
            } else {
                $null = $output.Append(" ")
            }
            $null = $output.Append($trimmed)
        }
    }
    if ($inParagraph) { $null = $output.Append("</p>") }
    $body = $output.ToString()

    # Restaurar code blocks
    for ($i = 0; $i -lt $script:codeBlocks.Count; $i++) {
        $body = $body.Replace("@@CODEBLOCK_$i@@", $script:codeBlocks[$i])
    }

    # Plantilla HTML
    $html = @"
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<title>$title</title>
<style>
@page { size: A4; margin: 18mm 15mm; }
body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    font-size: 10pt;
    line-height: 1.5;
    color: #24292e;
    max-width: 100%;
}
h1 { font-size: 22pt; border-bottom: 2px solid #eaecef; padding-bottom: 6px; margin-top: 24px; color: #1a1a1a; }
h2 { font-size: 16pt; border-bottom: 1px solid #eaecef; padding-bottom: 4px; margin-top: 20px; color: #1a1a1a; }
h3 { font-size: 13pt; margin-top: 16px; color: #1a1a1a; }
h4 { font-size: 11pt; margin-top: 12px; color: #1a1a1a; }
p { margin: 8px 0; }
code {
    background: #f6f8fa;
    padding: 1px 5px;
    border-radius: 3px;
    font-family: 'Consolas', 'Courier New', monospace;
    font-size: 9pt;
    color: #d6336c;
}
pre {
    background: #f6f8fa;
    padding: 10px 12px;
    border-radius: 5px;
    overflow-x: auto;
    font-size: 8.5pt;
    page-break-inside: avoid;
    border: 1px solid #e1e4e8;
}
pre code {
    background: transparent;
    color: #24292e;
    padding: 0;
    font-size: 8.5pt;
}
table {
    border-collapse: collapse;
    width: 100%;
    margin: 10px 0;
    font-size: 9pt;
    page-break-inside: avoid;
}
th, td {
    border: 1px solid #d0d7de;
    padding: 5px 8px;
    text-align: left;
    vertical-align: top;
}
th { background: #f6f8fa; font-weight: 600; }
tr:nth-child(even) td { background: #fafbfc; }
ul, ol { margin: 8px 0; padding-left: 24px; }
li { margin: 3px 0; }
hr { border: none; border-top: 1px solid #d0d7de; margin: 18px 0; }
blockquote {
    border-left: 4px solid #ddd;
    padding: 4px 12px;
    margin: 8px 0;
    color: #555;
    background: #fafbfc;
}
a { color: #0366d6; text-decoration: none; }
strong { font-weight: 600; }
</style>
</head>
<body>
$body
</body>
</html>
"@
    return $html
}

# Conversion de un MD a PDF
function Convert-MdToPdf {
    param([string]$mdFile)

    if (-not (Test-Path $mdFile)) {
        Write-Warning "No existe: $mdFile"
        return
    }

    $base = [System.IO.Path]::GetFileNameWithoutExtension($mdFile)
    $title = $base -replace '-', ' '
    $htmlFile = Join-Path $docsDir "$base.tmp.html"
    $pdfFile = Join-Path $docsDir "$base.pdf"

    Write-Host "Procesando $mdFile -> $pdfFile"

    $html = ConvertTo-Html -mdPath $mdFile -title $title
    Set-Content -Path $htmlFile -Value $html -Encoding UTF8

    # Llamar Edge headless para imprimir a PDF
    # Edge escribe warnings cosmeticos a stderr que PowerShell (con ErrorActionPreference=Stop)
    # trata como fatal aunque el PDF se genere correctamente. Por eso usamos cmd /c para
    # ejecutar Edge y descartar todo el stderr, despues validamos con Test-Path.
    $htmlUrl = "file:///" + ($htmlFile -replace '\\', '/')
    $edgeArgs = "--headless --disable-gpu --no-margins --print-to-pdf=`"$pdfFile`" --print-to-pdf-no-header `"$htmlUrl`""
    $null = cmd /c "`"$edge`" $edgeArgs 2>nul"

    if (Test-Path $pdfFile) {
        $size = (Get-Item $pdfFile).Length
        Write-Host "  OK ($([Math]::Round($size/1KB,1)) KB)" -ForegroundColor Green
        Remove-Item $htmlFile -ErrorAction SilentlyContinue
    } else {
        Write-Warning "  Fallo la generacion de $pdfFile"
    }
}

# Buscar todos los .md en docs/
$mdFiles = Get-ChildItem -Path $docsDir -Filter "*.md" -File
if ($mdFiles.Count -eq 0) {
    Write-Host "No se encontraron archivos .md en $docsDir"
    exit 0
}

foreach ($f in $mdFiles) {
    Convert-MdToPdf -mdFile $f.FullName
}

Write-Host ""
Write-Host "Listo. PDFs generados en $docsDir" -ForegroundColor Green
Get-ChildItem -Path $docsDir -Filter "*.pdf" | Format-Table Name, @{Name='KB';Expression={[Math]::Round($_.Length/1KB,1)}}, LastWriteTime
