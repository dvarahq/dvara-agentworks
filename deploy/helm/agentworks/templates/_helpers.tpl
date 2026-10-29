{{- define "agentworks.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- define "agentworks.fullname" -}}
{{- default (printf "%s-%s" .Release.Name (include "agentworks.name" .)) .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- define "agentworks.labels" -}}
app.kubernetes.io/name: {{ include "agentworks.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end }}
{{- define "agentworks.selectorLabels" -}}
app.kubernetes.io/name: {{ include "agentworks.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
{{- define "agentworks.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}{{ default (include "agentworks.fullname" .) .Values.serviceAccount.name }}{{ else }}{{ required "serviceAccount.name is required when create=false" .Values.serviceAccount.name }}{{ end }}
{{- end }}
