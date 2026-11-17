package model

type SystemSnapshot struct {
	NodeID      string
	Hostname    string
	IPAddress   string
	OSVersion   string
	CPUUsage    string
	MemoryUsage string
	DiskUsage   string
	LoadAverage string
	UptimeText  string
}

type LogEntry struct {
	NodeID  string
	Level   string
	Content string
}

type BaselineCheck struct {
	NodeID  string
	Name    string
	Status  string
	Message string
}
