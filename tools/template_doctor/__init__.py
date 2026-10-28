"""Template Doctor as an importable package (`tools.template_doctor`), for the weekly drift cron:
`from tools.template_doctor import run_report`. The CLI is `doctor.py`."""

from .doctor import run_report, run_report_sync

__all__ = ["run_report", "run_report_sync"]
