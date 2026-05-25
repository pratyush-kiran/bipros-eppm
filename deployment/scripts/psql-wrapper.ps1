# Windows counterpart of psql-wrapper.sh — but in PowerShell because cmd.exe
# can't preserve argument boundaries for SQL strings containing | or newlines.
#
# Args are passed via env vars PSQL_ARG_1 .. PSQL_ARG_<PSQL_ARGC> (set by the
# .cmd shim) instead of $args, because CMD's command-line pipe-operator parsing
# can't be reliably escaped from a .cmd into a child process. Stdin is forwarded
# (Spring/Python pipes SQL files through it).

$container = if ($env:BIPROS_PG_CONTAINER) { $env:BIPROS_PG_CONTAINER } else { 'bipros-postgres' }

# Reassemble args from env vars
$argc = [int]$env:PSQL_ARGC
$raw = @()
for ($i = 1; $i -le $argc; $i++) {
  $raw += [Environment]::GetEnvironmentVariable("PSQL_ARG_$i")
}

# Drop -h <val> and -p <val> pairs (in-container psql uses the local socket).
$out = @()
$i = 0
while ($i -lt $raw.Count) {
  $a = $raw[$i]
  if ($a -ieq '-h' -or $a -ieq '-p') { $i += 2; continue }
  $out += $a
  $i++
}

$dockerArgs = @('exec', '-i')
if ($env:PGPASSWORD) { $dockerArgs += @('-e', "PGPASSWORD=$($env:PGPASSWORD)") }
$dockerArgs += @($container, 'psql')
$dockerArgs += $out

& docker @dockerArgs
exit $LASTEXITCODE
