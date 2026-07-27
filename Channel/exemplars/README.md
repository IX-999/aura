# Style exemplars

Drop transcripts of videos whose STYLE you want the script agent to imitate here,
as `.txt` or `.md` files (this README is ignored).

How it works (see `com.agent.agent.ExemplarLibrary`):
- Loaded once at app startup and appended to the agent's instruction as few-shot
  style examples — voice, pacing, hooks, structure. Subject matter and facts are
  explicitly NOT copied; research still drives all factual claims.
- Each file is screened by the same deterministic `ContentPolicy` the agent obeys.
  A BLOCK verdict (exploitative content, instructions for wrongdoing, targeted
  harassment, celebratory/graphic violence) skips the file with a warning in the log.
- Limits: first 3 files (alphabetical), 8,000 characters each.

Restart the app after adding or changing files.
