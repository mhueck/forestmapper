<#
.SYNOPSIS
    Builds app/src/main/assets/forest_catalog.json from the USFS Digital Maps
    ArcGIS Experience Builder Forest Series index without downloading the PDFs.

.DESCRIPTION
    1. Resolves the Forest Series FeatureServer layer URL from ArcGIS Experience
       Builder item 9ab8d03e2bec4d7fbfc27ba836e70aed (with a direct fallback).
    2. Queries all Forest Series map footprints in WGS84 (EPSG:4326) with
       pagination support.
    3. Builds the USFS downloadMap.php PDF URL for each map and (unless
       -SkipUrlCheck is passed) issues HTTP HEAD requests to resolve the
       redirected PDF URL, file size, ETag, and Last-Modified timestamp.
    4. Outputs a deterministic, UTF-8 (no BOM) JSON catalog for the Android app.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\build-forest-catalog.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\build-forest-catalog.ps1 -SkipUrlCheck
#>

[CmdletBinding()]
param(
    [string]$OutputPath = "",
    [string]$ExperienceItemId = "9ab8d03e2bec4d7fbfc27ba836e70aed",
    [string]$WhereClause = "mapseries = 'Forest'",
    [string]$FeatureLayerUrl = "",
    [switch]$SkipUrlCheck,
    [int]$TimeoutSeconds = 20
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $OutputPath) {
    $OutputPath = Join-Path $ScriptDir "..\app\src\main\assets\forest_catalog.json"
}

function Get-JsonProp {
    param($Obj, [string]$Name, $Default = $null)
    if ($null -eq $Obj) { return $Default }
    $prop = $Obj.PSObject.Properties[$Name]
    if ($null -ne $prop) { return $prop.Value }
    return $Default
}

# Ensure TLS 1.2+ on Windows PowerShell 5.1
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
} catch {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
}

Add-Type -AssemblyName System.Net.Http

$FallbackFeatureLayerUrl = "https://services1.arcgis.com/gGHDlz6USftL5Pau/arcgis/rest/services/RD_Forest_Extents/FeatureServer/0"
$ExperiencePageUrl = "https://experience.arcgis.com/experience/$ExperienceItemId/page/Forest-Series"

function New-HttpClient {
    param([bool]$AllowAutoRedirect, [int]$TimeoutSec)
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $AllowAutoRedirect
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $client.DefaultRequestHeaders.UserAgent.ParseAdd("GeoPdfMapperCatalogBuilder/1.0")
    return $client
}

function Invoke-GetJson {
    param([System.Net.Http.HttpClient]$Client, [string]$Url)
    $resp = $Client.GetAsync($Url).GetAwaiter().GetResult()
    if (-not $resp.IsSuccessStatusCode) {
        throw "GET $Url failed with HTTP $([int]$resp.StatusCode) $($resp.ReasonPhrase)"
    }
    $body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    return ($body | ConvertFrom-Json)
}

function Resolve-ForestFeatureLayerUrl {
    param([System.Net.Http.HttpClient]$Client, [string]$ItemId)
    try {
        $exbUrl = "https://www.arcgis.com/sharing/rest/content/items/$ItemId/data?f=json"
        $exb = Invoke-GetJson -Client $Client -Url $exbUrl

        $forestWidget = $exb.widgets.PSObject.Properties |
            ForEach-Object { $_.Value } |
            Where-Object {
                (Get-JsonProp $_ "label") -eq "Forest Map" -and (Get-JsonProp $_ "useDataSources")
            } |
            Select-Object -First 1

        if ($forestWidget) {
            $dsId = $forestWidget.useDataSources[0].dataSourceId
            $dsObj = Get-JsonProp $exb.dataSources $dsId
            $webMapItemId = Get-JsonProp $dsObj "itemId"
            if ($webMapItemId) {
                $wmUrl = "https://www.arcgis.com/sharing/rest/content/items/$webMapItemId/data?f=json"
                $wm = Invoke-GetJson -Client $Client -Url $wmUrl
                $layer = $wm.operationalLayers | Where-Object {
                    $ld = Get-JsonProp $_ "layerDefinition"
                    $defExpr = Get-JsonProp $ld "definitionExpression"
                    ($defExpr -and $defExpr -match "mapseries\s*=\s*'Forest'") -or
                    (Get-JsonProp $_ "title") -eq "RD_Forest_Extents"
                } | Select-Object -First 1
                $layerUrlProp = Get-JsonProp $layer "url"
                if ($layerUrlProp) {
                    return $layerUrlProp
                }
            }
        }
    } catch {
        Write-Warning "Could not auto-discover FeatureServer URL from Experience Builder ($($_.Exception.Message)); using fallback."
    }
    return $FallbackFeatureLayerUrl
}

function Get-AllForestFeatures {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$LayerUrl,
        [string]$Where
    )

    $meta = Invoke-GetJson -Client $Client -Url "$LayerUrl`?f=json"
    $maxRec = Get-JsonProp $meta "maxRecordCount"
    $pageSize = if ($maxRec -and [int]$maxRec -gt 0) { [Math]::Min([int]$maxRec, 1000) } else { 500 }

    $allFeatures = New-Object System.Collections.Generic.List[object]
    $offset = 0
    $encodedWhere = [Uri]::EscapeDataString($Where)

    while ($true) {
        $queryUrl = "$LayerUrl/query?where=$encodedWhere&outFields=*&outSR=4326&orderByFields=objectid&resultOffset=$offset&resultRecordCount=$pageSize&f=geojson"
        $page = Invoke-GetJson -Client $Client -Url $queryUrl
        $pageFeatures = Get-JsonProp $page "features"
        if (-not $pageFeatures -or $pageFeatures.Count -eq 0) {
            break
        }
        foreach ($f in $pageFeatures) {
            $allFeatures.Add($f)
        }
        $exceeded = $false
        $pageProps = Get-JsonProp $page "properties"
        if ($pageProps) {
            $exceeded = [bool](Get-JsonProp $pageProps "exceededTransferLimit" $false)
        }
        if (-not $exceeded -and $pageFeatures.Count -lt $pageSize) {
            break
        }
        $offset += $pageFeatures.Count
    }

    return $allFeatures
}

function New-SlugId {
    param([string]$Name)
    $slug = $Name.ToLowerInvariant()
    $slug = [Regex]::Replace($slug, "[^a-z0-9]+", "-").Trim("-")
    return $slug
}

function ConvertTo-EncodedUrl {
    param([string]$RawUrl)
    # Encode spaces and unsafe characters in redirect Location headers while preserving scheme/host/query
    try {
        $uri = New-Object System.Uri($RawUrl)
        return $uri.AbsoluteUri
    } catch {
        return ($RawUrl -replace " ", "%20")
    }
}

function Get-UrlMetadata {
    param(
        [System.Net.Http.HttpClient]$NoRedirectClient,
        [System.Net.Http.HttpClient]$RedirectClient,
        [string]$DownloadUrl,
        [string]$FallbackFileName
    )

    $result = [ordered]@{
        resolvedUrl   = $null
        fileName      = $FallbackFileName
        fileSizeBytes = $null
        contentType   = $null
        etag          = $null
        lastModified  = $null
        urlStatus     = "unchecked"
    }

    $maxAttempts = 2
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        try {
            $req1 = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Head, $DownloadUrl)
            $resp1 = $NoRedirectClient.SendAsync($req1, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
            $code1 = [int]$resp1.StatusCode

            if ($code1 -eq 405) {
                $resp1.Dispose()
                $req1 = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, $DownloadUrl)
                $resp1 = $NoRedirectClient.SendAsync($req1, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                $code1 = [int]$resp1.StatusCode
            }

            $targetUrl = $DownloadUrl
            $finalResp = $resp1

            if ($code1 -ge 300 -and $code1 -lt 400 -and $resp1.Headers.Location) {
                $locRaw = $resp1.Headers.Location.ToString()
                if (-not $resp1.Headers.Location.IsAbsoluteUri) {
                    $baseUri = New-Object System.Uri($DownloadUrl)
                    $locRaw = (New-Object System.Uri($baseUri, $locRaw)).AbsoluteUri
                }
                $targetUrl = ConvertTo-EncodedUrl -RawUrl $locRaw
                $resp1.Dispose()

                $req2 = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Head, $targetUrl)
                $finalResp = $RedirectClient.SendAsync($req2, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                if ([int]$finalResp.StatusCode -eq 405) {
                    $finalResp.Dispose()
                    $req2 = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, $targetUrl)
                    $finalResp = $RedirectClient.SendAsync($req2, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                }
            }

            $result.resolvedUrl = $targetUrl
            if ($finalResp.IsSuccessStatusCode) {
                if ($finalResp.Content.Headers.ContentType) {
                    $result.contentType = $finalResp.Content.Headers.ContentType.MediaType
                }
                if ($null -ne $finalResp.Content.Headers.ContentLength) {
                    $result.fileSizeBytes = [long]$finalResp.Content.Headers.ContentLength
                }
                if ($finalResp.Headers.ETag) {
                    $result.etag = $finalResp.Headers.ETag.ToString()
                }
                if ($null -ne $finalResp.Content.Headers.LastModified) {
                    $result.lastModified = $finalResp.Content.Headers.LastModified.UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ssZ")
                }
                $dispName = $null
                if ($finalResp.Content.Headers.ContentDisposition -and $finalResp.Content.Headers.ContentDisposition.FileName) {
                    $dispName = $finalResp.Content.Headers.ContentDisposition.FileName.Trim('"')
                }
                if ($dispName) {
                    $result.fileName = $dispName
                } else {
                    try {
                        $leaf = [Uri]::UnescapeDataString([System.IO.Path]::GetFileName((New-Object System.Uri($targetUrl)).AbsolutePath))
                        if ($leaf) { $result.fileName = $leaf }
                    } catch { }
                }

                if ($result.contentType -and $result.contentType -notmatch "pdf") {
                    $result.urlStatus = "unexpected_content_type:$($result.contentType)"
                } else {
                    $result.urlStatus = "ok"
                }
            } else {
                $result.urlStatus = "http_$([int]$finalResp.StatusCode)"
            }

            $finalResp.Dispose()
            return $result
        } catch {
            if ($attempt -eq $maxAttempts) {
                $result.urlStatus = "error:$($_.Exception.Message)"
                return $result
            }
            Start-Sleep -Milliseconds 400
        }
    }

    return $result
}

function Convert-RingToCatalogGeometry {
    param($RingCoords)

    $polygon = New-Object System.Collections.Generic.List[object]
    $minLat = [double]::PositiveInfinity
    $maxLat = [double]::NegativeInfinity
    $minLon = [double]::PositiveInfinity
    $maxLon = [double]::NegativeInfinity

    foreach ($pt in $RingCoords) {
        $lon = [Math]::Round([double]$pt[0], 6)
        $lat = [Math]::Round([double]$pt[1], 6)
        if ($lat -lt $minLat) { $minLat = $lat }
        if ($lat -gt $maxLat) { $maxLat = $lat }
        if ($lon -lt $minLon) { $minLon = $lon }
        if ($lon -gt $maxLon) { $maxLon = $lon }

        $polygon.Add([ordered]@{
            lat = $lat
            lon = $lon
        })
    }

    # Compute centroid from unique ring vertices (skip closing vertex if repeated)
    $vertexCount = $polygon.Count
    if ($vertexCount -gt 1) {
        $first = $polygon[0]
        $last = $polygon[$vertexCount - 1]
        if ($first.lat -eq $last.lat -and $first.lon -eq $last.lon) {
            $vertexCount -= 1
        }
    }
    $sumLat = 0.0
    $sumLon = 0.0
    for ($i = 0; $i -lt $vertexCount; $i++) {
        $sumLat += $polygon[$i].lat
        $sumLon += $polygon[$i].lon
    }
    $centroidLat = if ($vertexCount -gt 0) { [Math]::Round($sumLat / $vertexCount, 6) } else { [Math]::Round(($minLat + $maxLat) * 0.5, 6) }
    $centroidLon = if ($vertexCount -gt 0) { [Math]::Round($sumLon / $vertexCount, 6) } else { [Math]::Round(($minLon + $maxLon) * 0.5, 6) }

    return [ordered]@{
        bbox = [ordered]@{
            minLat = $minLat
            maxLat = $maxLat
            minLon = $minLon
            maxLon = $maxLon
        }
        centroid = [ordered]@{
            lat = $centroidLat
            lon = $centroidLon
        }
        polygon = $polygon.ToArray()
    }
}

$redirectClient = New-HttpClient -AllowAutoRedirect $true -TimeoutSec $TimeoutSeconds
$noRedirectClient = New-HttpClient -AllowAutoRedirect $false -TimeoutSec $TimeoutSeconds

try {
    if (-not $FeatureLayerUrl) {
        Write-Host "Resolving Forest Series FeatureServer layer from ArcGIS Experience Builder..."
        $FeatureLayerUrl = Resolve-ForestFeatureLayerUrl -Client $redirectClient -ItemId $ExperienceItemId
    }
    Write-Host "Querying features from: $FeatureLayerUrl (where: $WhereClause)"

    $features = Get-AllForestFeatures -Client $redirectClient -LayerUrl $FeatureLayerUrl -Where $WhereClause
    Write-Host "Fetched $($features.Count) Forest Series map footprints."

    $sortedFeatures = @($features | Sort-Object { $_.properties.mapname })
    $maps = New-Object System.Collections.Generic.List[object]
    $seenIds = New-Object System.Collections.Generic.HashSet[string]

    $total = $sortedFeatures.Count
    $idx = 0
    foreach ($f in $sortedFeatures) {
        $idx++
        $p = $f.properties
        $mapName = [string]$p.mapname
        if (-not $mapName) { continue }

        if (-not $f.geometry -or -not $f.geometry.coordinates) {
            Write-Warning "Skipping '$mapName': missing geometry."
            continue
        }

        $ring = $null
        if ($f.geometry.type -eq "Polygon") {
            $ring = $f.geometry.coordinates[0]
        } elseif ($f.geometry.type -eq "MultiPolygon") {
            $ring = $f.geometry.coordinates[0][0]
        } else {
            Write-Warning "Skipping '$mapName': unsupported geometry type '$($f.geometry.type)'."
            continue
        }

        $geom = Convert-RingToCatalogGeometry -RingCoords $ring

        $baseSlug = New-SlugId -Name $mapName
        $id = $baseSlug
        $suffix = 2
        while (-not $seenIds.Add($id)) {
            $id = "$baseSlug-$suffix"
            $suffix++
        }

        $downloadUrl = "https://data.fs.usda.gov/geodata/rastergateway/downloadMap.php?mapID=$([Uri]::EscapeDataString($mapName))&mapType=pdf&seriesType=Forest"
        $defaultFileName = "$mapName.pdf"

        if ($SkipUrlCheck) {
            $urlMeta = [ordered]@{
                resolvedUrl   = $null
                fileName      = $defaultFileName
                fileSizeBytes = $null
                contentType   = "application/pdf"
                etag          = $null
                lastModified  = $null
                urlStatus     = "unchecked"
            }
        } else {
            Write-Progress -Activity "Verifying USFS PDF download URLs" -Status "[$idx/$total] $mapName" -PercentComplete ([int](($idx * 100) / $total))
            $urlMeta = Get-UrlMetadata -NoRedirectClient $noRedirectClient -RedirectClient $redirectClient -DownloadUrl $downloadUrl -FallbackFileName $defaultFileName
        }

        $scaleRaw = Get-JsonProp $p "map_scale_1"
        $scaleVal = if ($null -ne $scaleRaw) { [int][Math]::Round([double]$scaleRaw) } else { $null }
        $commonName = Get-JsonProp $p "commonname"
        $adminUnit = Get-JsonProp $p "adminunit"
        $region = Get-JsonProp $p "region"
        $stateName = Get-JsonProp $p "statename"
        $series = Get-JsonProp $p "mapseries"

        $entry = [ordered]@{
            id            = $id
            objectId      = [int](Get-JsonProp $p "objectid" 0)
            mapName       = $mapName
            commonName    = if ($commonName) { [string]$commonName } else { $mapName }
            adminUnit     = if ($adminUnit) { [string]$adminUnit } else { $null }
            series        = if ($series) { [string]$series } else { "Forest" }
            region        = if ($region) { [string]$region } else { $null }
            state         = if ($stateName) { [string]$stateName } else { $null }
            scale         = $scaleVal
            bbox          = $geom.bbox
            centroid      = $geom.centroid
            polygon       = $geom.polygon
            downloadUrl   = $downloadUrl
            resolvedUrl   = $urlMeta.resolvedUrl
            fileName      = $urlMeta.fileName
            fileSizeBytes = $urlMeta.fileSizeBytes
            contentType   = $urlMeta.contentType
            etag          = $urlMeta.etag
            lastModified  = $urlMeta.lastModified
            urlStatus     = $urlMeta.urlStatus
        }

        $maps.Add($entry)
    }

    if (-not $SkipUrlCheck) {
        Write-Progress -Activity "Verifying USFS PDF download URLs" -Completed
    }

    $catalog = [ordered]@{
        schemaVersion = 1
        generatedAt   = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
        source        = [ordered]@{
            experienceUrl   = $ExperiencePageUrl
            featureLayerUrl = $FeatureLayerUrl
            whereClause     = $WhereClause
        }
        mapCount      = $maps.Count
        maps          = $maps.ToArray()
    }

    $resolvedOut = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
    $outDir = [System.IO.Path]::GetDirectoryName($resolvedOut)
    if (-not (Test-Path $outDir)) {
        New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    }

    $jsonText = $catalog | ConvertTo-Json -Depth 10
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($resolvedOut, $jsonText + "`n", $utf8NoBom)

    [long]$totalBytes = 0
    $statusCounts = @{}
    foreach ($m in $maps) {
        if ($null -ne $m.fileSizeBytes) {
            $totalBytes += [long]$m.fileSizeBytes
        }
        $st = [string]$m.urlStatus
        if ($statusCounts.ContainsKey($st)) {
            $statusCounts[$st] = $statusCounts[$st] + 1
        } else {
            $statusCounts[$st] = 1
        }
    }
    $totalGb = if ($totalBytes -gt 0) { [Math]::Round($totalBytes / 1GB, 2) } else { 0 }
    $statusSummary = ($statusCounts.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ", "

    Write-Host ""
    Write-Host "Wrote $($maps.Count) maps to: $resolvedOut"
    Write-Host "URL status: $statusSummary"
    if ($totalBytes) {
        Write-Host "Combined PDF size across verified maps: $totalGb GB"
    }
} finally {
    $redirectClient.Dispose()
    $noRedirectClient.Dispose()
}
