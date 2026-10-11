{{- define "spectrace.fullname" -}}
{{- printf "%s-%s" .Release.Name .Values.serviceName | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "spectrace.selectorLabels" -}}
app.kubernetes.io/name: {{ .Values.serviceName | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}
{{- define "spectrace.labels" -}}
{{ include "spectrace.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end -}}
