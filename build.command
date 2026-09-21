#!/bin/zsh
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "Java 21 is required."
  exit 1
fi

if command -v gradle >/dev/null 2>&1; then
  gradle build
  status=$?
  if [ $status -eq 0 ]; then
    echo
    echo "Built JARs are in: build/libs/"
  fi
  exit $status
fi

echo "Gradle is not installed on this Mac."
echo "This project includes a GitHub Actions workflow that builds it with Gradle 8.8 + Java 21 without needing Gradle installed locally."
echo "Upload the project to GitHub, open Actions -> Build Fabric 1.21 Mod -> Run workflow."
exit 2
