{{/* generates internal LAPIS urls from given config object */}}
{{ define "loculus.generateInternalLapisUrls"}}
{{ range $key, $_ := $.Values.instances }}
"{{ $key -}}": "http://{{ template "loculus.lapisServiceName" $key }}:8080"
{{ end }}
{{ end }}

{{/* generates external LAPIS urls from { config, host } */}}
{{ define "loculus.generateExternalLapisUrls"}}
{{ $host := .host }}
{{ range $key, $_ := .config.instances }}
"{{ $key -}}": "{{ $host }}/{{ $key }}"
{{ end }}
{{ end }}

{{/* generates the LAPIS service name for a given organism key */}}
{{- define "loculus.lapisServiceName"}}
{{- printf "loculus-lapis-service-%s" . }}
{{- end }}
