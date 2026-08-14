param([switch]$ReportOnly)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$langDir=Join-Path $root 'src/main/resources/assets/townymapaddon/lang'
$codes=@('en_us','zh_cn','zh_tw','ja_jp')
$catalogs=@{}
foreach($code in $codes){$obj=Get-Content -Raw -Encoding UTF8 (Join-Path $langDir "$code.json")|ConvertFrom-Json;$map=@{};foreach($p in $obj.PSObject.Properties){$map[$p.Name]=$p.Value};$catalogs[$code]=$map}
$errors=[Collections.Generic.List[string]]::new()
$baseKeys=@($catalogs.en_us.Keys|Sort-Object)
foreach($code in $codes){$keys=@($catalogs[$code].Keys|Sort-Object);$missing=@($baseKeys|Where-Object{$_-notin$keys});$extra=@($keys|Where-Object{$_-notin$baseKeys});if($missing.Count-or$extra.Count){$errors.Add("$code key mismatch: missing=$($missing.Count), extra=$($extra.Count)")};foreach($key in $keys){$value=[string]$catalogs[$code][$key];if([string]::IsNullOrWhiteSpace($value)){$errors.Add("$code blank: $key")};if($value-match'[\u00C2\u00C3\uFFFD]|\u00E2\u20AC'){$errors.Add("$code encoding damage: $key")}}}
function Placeholders([string]$value){return @([regex]::Matches($value,'%(?:\d+\$)?[a-zA-Z]')|ForEach-Object Value|Sort-Object)}
foreach($key in $baseKeys){$expected=(Placeholders ([string]$catalogs.en_us[$key]))-join',';foreach($code in $codes|Where-Object{$_-ne'en_us'}){$actual=(Placeholders ([string]$catalogs[$code][$key]))-join',';if($actual-ne$expected){$errors.Add("$code placeholder mismatch: $key expected=[$expected] actual=[$actual]")}}}
$sameWhitelist='^(HunterAlert|Dynmap|Xaero|Squaremap|Squaremap…|EarthMC|EMC|API|UUID|RGB|NPC|PVP|Discord|Wiki|ON|OFF|N|NE|E|SE|S|SW|W|NW)$'
$sameKeyWhitelist='^(townymapaddon\.hunter\.watch\.names_hint|townymapaddon\.hunter\.event\.from_to|townymapaddon\.planning\.summary|townymapaddon\.map_controls\.squaremap_loading)$'
foreach($code in @('zh_cn','zh_tw','ja_jp')){foreach($key in $baseKeys){$value=[string]$catalogs[$code][$key];if($value-eq[string]$catalogs.en_us[$key]-and$value-notmatch$sameWhitelist-and$key-notmatch$sameKeyWhitelist){$errors.Add("$code untranslated: $key = $value")}}}
$javaRoots=@((Join-Path $root 'src/main/java/net/townymap/gui'),(Join-Path $root 'src/main/java/net/townymap/hunter'),(Join-Path $root 'src/main/java/net/townymap/TownyMapMod.java'))
$literalPatterns=@('Component\.literal\("[A-Za-z][^"\\]*"','(?:\.text|\.centeredText)\([^\r\n]*,"[A-Za-z][^"\\]*"','Button\.builder\(Component\.literal\("[A-Za-z]','(?:option|section|action)\("[A-Za-z][^"\\]*"','(?:out\.add|chat\.accept)\("(?:§.)*[A-Za-z]','new (?:Col|Wide|LegacyLine|LineList|RefLineList)\("[A-Za-z][^"\\]*"','drawButton\([^\r\n]*,"[A-Za-z][^"\\]*"','drawTexturedButton\([^\r\n]*,"[A-Za-z][^"\\]*"','(?:addNames|rankList|Col\.live)\([^\r\n]*"[A-Za-z][^"\\]*"','new Page\([^\r\n]*"[A-Za-z][^"\\]*"','InfoRow\.(?:text|link)\("(?:§.)*[A-Za-z][^"\\]*"','pendingTipText\s*=\s*"[A-Za-z][^"\\]*"')
foreach($path in $javaRoots){$files=if(Test-Path $path -PathType Leaf){Get-Item $path}else{Get-ChildItem $path -Recurse -Filter *.java};foreach($file in $files){$text=Get-Content -Raw -Encoding UTF8 $file.FullName;foreach($pattern in $literalPatterns){foreach($m in [regex]::Matches($text,$pattern)){if($m.Value-match'Component\.literal\("(?:Discord|Wiki|dd/mm/yyyy)"'-or$m.Value-match'Component\.translatable'-or$m.Value-match'(?:label|msg)\(') {continue};$line=1+($text.Substring(0,$m.Index).Split("`n").Count-1);$errors.Add("hard-coded UI: $($file.FullName.Substring($root.Length+1)):$line $($m.Value)")}}}}
$allJava=Get-ChildItem (Join-Path $root 'src/main/java/net/townymap') -Recurse -Filter *.java
foreach($file in $allJava){$text=Get-Content -Raw -Encoding UTF8 $file.FullName;foreach($m in [regex]::Matches($text,'"(townymapaddon\.[a-z0-9_.]+)"')){$key=$m.Groups[1].Value;if($key-notmatch'\.$|\.json$'-and-not$catalogs.en_us.ContainsKey($key)){$errors.Add("missing English key: $key in $($file.FullName.Substring($root.Length+1))")}}}
Write-Host "Localization keys: en_us=$($catalogs.en_us.Count), zh_cn=$($catalogs.zh_cn.Count), zh_tw=$($catalogs.zh_tw.Count), ja_jp=$($catalogs.ja_jp.Count)"
if($errors.Count){$errors|Sort-Object -Unique|ForEach-Object{Write-Host $_};Write-Host "Localization audit failures: $($errors.Count)";if(!$ReportOnly){exit 1}}else{Write-Host 'Localization audit passed.'}
