{{- define "loculus.preprocessingSpecs" -}}

{{- $metadata := .metadata }}
{{- $segments := .nucleotideSequences}}
{{- $use_segments := false }} # Default to false
{{- if or (lt (len $segments) 1) (and (eq (len $segments) 1) (eq (index $segments 0) "main")) }}
{{- $use_segments = false }}
{{- else }}
{{- $use_segments = true }}
{{- end }}

{{- range $metadata }}
{{- $currentItem := . }}
{{- if and $use_segments .perSegment }}
{{- range $segment := $segments }}
{{ printf "%s_%s:" $currentItem.name $segment}}
  args:
    segment: {{ $segment }}
    {{- if $currentItem.type }}
    type: {{ $currentItem.type }}
    {{- end }}
    {{- if $currentItem.noInput }}
    no_warn: {{ $currentItem.noInput }}
    {{- end }}
  {{- if $currentItem.preprocessing }}
  {{- if hasKey $currentItem.preprocessing "function" }}
  function: {{ index $currentItem.preprocessing "function" }}
  {{- else }}
  function: identity
  {{- end }}
  {{- if hasKey $currentItem.preprocessing "inputs" }}
  inputs: 
    {{- with index $currentItem.preprocessing "inputs" }}
    {{- . | toYaml | nindent 4 }}
    {{- end }}
  {{- end }}
  {{- else }}
  function: identity
  inputs: 
    input: {{ printf "%s_%s" $currentItem.name $segment }}
  {{- end }}
{{- end}}

{{- else }}
{{ printf "%s:" .name }}
  args:
    {{- if .type }}
    type: {{ .type }}
    {{- end }}
    {{- if .noInput }}
    no_warn: {{ .noInput }}
    {{- end }}
  {{- if .preprocessing }}
  {{- if hasKey .preprocessing "function" }}
  function: {{ index .preprocessing "function" }}
  {{- else }}
  function: identity
  {{- end }}
  {{- if hasKey .preprocessing "inputs" }}
  inputs: 
    {{- with index .preprocessing "inputs" }}
    {{- . | toYaml | nindent 4 }}
    {{- end }}
  {{- end }}
  {{- else }}
  function: identity
  inputs: 
    input: {{ .name }}
  {{- end }}
  {{- end }}

{{- end }}
{{- end }}
