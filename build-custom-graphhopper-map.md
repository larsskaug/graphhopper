# Custom GraphHopper UI Integration Guide

**Goal:** Bundle a modified version of `graphhopper-maps` (JS) into the `graphhopper` (Java) server.
**Prerequisites:** You have both repositories checked out side-by-side (e.g., in the same parent directory).

## Phase 1: One-Time Configuration (Crucial)

Before running your first build, you must disable the official GraphHopper build step that downloads the default UI from the internet. If you skip this, Maven will overwrite your custom work.

1. Open `graphhopper/web-bundle/pom.xml`.

2. Locate the `<plugins>` section.

3. **Delete** (or comment out) the entire blocks for:

   * `frontend-maven-plugin` (This installs Node and downloads the official maps).

   * `maven-antrun-plugin` (This unzips the official maps over your files).

**Tip:** It is often safer to delete these lines than to comment them out, because XML does not allow nested comments (comments inside comments) which causes syntax errors.

## Phase 2: The Build Workflow

Run these commands whenever you make changes to the JavaScript UI.

### 1. Build the Frontend

Navigate to your maps repository and create the production bundle.

```bash
cd graphhopper-maps
npm install
npm run build
# Go back to parent directory
cd ..
````

### 2\. Clean Old Bundle Files (Safely)

Remove the old Javascript/CSS files from the Java source tree to prevent clutter (e.g., `bundle.oldhash.js` vs `bundle.newhash.js`).

**Note:** We use `find` here instead of `rm -rf` to ensure we **only delete files** and preserve the specialized subdirectories (`pt/`, `isochrone/`, `map-matching/`) that live in the same folder.

```bash
# Delete only files in the root of the maps directory, leaving folders untouched
find web-bundle/src/main/resources/com/graphhopper/maps/ -maxdepth 1 -type f -delete
```

### 3\. Copy New UI

Copy your newly built files into the Java resource tree. The `-r` flag ensures assets are copied, and the logic merges them alongside the existing specialized subdirectories.

```bash
cp -r ../graphhopper-maps/dist/* web-bundle/src/main/resources/com/graphhopper/maps/
```

### 4\. Build the Backend

Compile the Java project. Since we modified the `pom.xml`, Maven will simply bundle whatever files are currently in the resources folder (which are now your custom files).

```bash
cd graphhopper
mvn clean package -DskipTests
```

## Phase 3: Run and Verify

Your self-contained JAR file is now ready.

1.  **Locate the JAR:**
    `graphhopper/web/target/graphhopper-web-*.jar`

2.  **Run it:**

    ```bash
    java -Ddw.graphhopper.datareader.file=your-osm-file.pbf -jar web/target/graphhopper-web-*.jar server config.yml
    ```

3.  **Check the Browser:**
    Open `http://localhost:8989`. You should see your custom changes (verify by checking the page source or a specific visual change you made).
