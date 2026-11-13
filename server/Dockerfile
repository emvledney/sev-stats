FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY sevstats ./sevstats
RUN useradd --system --uid 10001 sev && mkdir /data && chown sev /data
USER sev
VOLUME /data
ENV SEV_DATA=/data
ENTRYPOINT ["python", "-m", "sevstats"]
