"""Select the phone's portable numerical backend explicitly, without silent fallback."""

import os

if os.getenv("RADAR_PHONE_SCIENCE") == "1":
    from .portable_science import expit, fisher_exact, minimize, norm, student_t
else:
    from scipy.optimize import minimize
    from scipy.special import expit
    from scipy.stats import fisher_exact, norm, t as student_t

__all__ = ["expit", "fisher_exact", "minimize", "norm", "student_t"]
