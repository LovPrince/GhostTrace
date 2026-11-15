# 🚀 GhostTrace — GitHub Deployment Guide
by @princeyy

---

## Step 1 — Create GitHub repo

1. Go to https://github.com/new
2. Name it: `GhostTrace`
3. Set to **Public** (or Private)
4. ❌ Do NOT add README / .gitignore (project has them)
5. Click **Create repository**

---

## Step 2 — Push from Termux

```bash
# Install git if not already
pkg install git

# Go to project folder (after extracting the zip)
cd ~/GhostTrace

# Init git
git init
git branch -M main

# Add your GitHub remote (replace YOUR_USERNAME)
git remote add origin https://github.com/YOUR_USERNAME/GhostTrace.git

# Stage everything
git add .

# First commit
git commit -m "🚀 Initial commit — GhostTrace by @princeyy"

# Push
git push -u origin main
```

> First push will ask for GitHub username + password.
> Use a **Personal Access Token** as the password (not your GitHub password).
> Generate one at: https://github.com/settings/tokens → New token → repo scope

---

## Step 3 — Watch the build

1. Go to your repo on GitHub
2. Click the **Actions** tab
3. You'll see **GhostTrace CI** running
4. Build takes ~3-5 minutes

---

## Step 4 — Download your APK

**Debug APK (every build):**
- Actions tab → click the latest run → scroll to **Artifacts**
- Download `GhostTrace-debug-N`

**Release APK (auto-published):**
- Go to **Releases** tab on your repo
- Download `app-release-unsigned.apk`
- Install on device (enable "Unknown apps" first)

---

## Every future push

```bash
git add .
git commit -m "your message"
git push
```
→ GitHub Actions auto-builds and publishes a new release. Done.

---

## CI workflow summary

| Trigger | Job | Output |
|---|---|---|
| Any push to `main` | Build debug APK | Uploaded as artifact (30 days) |
| Any push to `main` | Build release APK | Published as GitHub Release |
| Pull request | Build debug only | Artifact only, no release |

---

## Token setup (one-time)

```bash
# In Termux, store credentials so you don't retype every push
git config --global credential.helper store
# Next push will ask once, then remember
```
