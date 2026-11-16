# Garmin FIT SDK Licence: Redistribution in a Jar or Container Image

**Date:** 2026-11-16
**Status:** Research conclusion (not legal advice)
**Question:** Stridehearth is going public under Apache-2.0 (ADR-0012) and depends on `com.garmin:fit:21.217.0`. May the source repository, the Spring Boot jar, and a container image (ADR-0009) carry the FIT SDK, and what notice is needed?

---

## Conclusion

1. **The source repository is fine.** It contains no FIT SDK code. `build.gradle.kts` names the Maven coordinate and Gradle downloads the jar from Maven Central at build time. Stridehearth's own code can be Apache-2.0: the FIT licence restricts the SDK, not code that calls it.
2. **Shipping the unmodified jar inside the Boot jar or a container image is permitted, on Garmin's stated reading of the licence.** The licence text alone is ambiguous: §1 grants use "in any software created by Licensee", while §2(c) forbids distributing the Licensed Technology "except as set forth herein". Garmin's FIT SDK maintainer (the account that posts Garmin's official FIT SDK announcements, including the 2022 licence update) has answered this exact question twice on Garmin's developer forum: bundling the SDK in an application is "allowed by the license agreement", because §1 expressly permits use in software created by the licensee and "section 2c doesn't prohibit anything that is expressly permitted". Garmin also publishes the SDK on Maven Central, npm, PyPI and NuGet, and changed the licence in 2022 so that it can be used "in places like Github, PyPI, npm, NuGet".
3. **Conditions Stridehearth must keep:**
   - Ship the SDK **unmodified**, and never remove or change Garmin's notices or marks (§2(a)). Any modification must keep FIT interoperability (§3) and is licensed back to Garmin (§5).
   - Never put the SDK itself under a licence that requires source disclosure or grants modification rights (§2(d)). Apache-2.0 does neither, and it applies only to Stridehearth's own code. `NOTICE` says plainly that the SDK is **not** under Apache-2.0 and points to the FIT licence.
   - Do not vendor the SDK sources or jar into the repository; keep resolving it from Maven Central.
   - Do not present Stridehearth for safety-critical use (§2(g)).
4. **Residual risk.** The permission to bundle comes from Garmin forum replies, not from the licence text, and both threads are still marked "Not Answered". Garmin may also amend the licence (§11(g)) or terminate it on 30 days' notice (§9). If Garmin ever objects, the fallback is to stop publishing prebuilt jars and images and let self-hosters build from source, which downloads the SDK themselves under their own acceptance of the licence. Asking Garmin for written confirmation before the first published image would close this gap.

## Sources

- FIT Protocol License Agreement, last updated 12 Oct 2022 (the licence named in the `com.garmin:fit` POM): <https://github.com/garmin/fit-java-sdk/blob/main/LICENSE.txt>
- `com.garmin:fit:21.217.0` POM on Maven Central (licence name and URL): <https://repo1.maven.org/maven2/com/garmin/fit/21.217.0/fit-21.217.0.pom>
- Garmin forum, "Can a commercial Android app bundle the FIT Java SDK (com.garmin:fit) for distribution?", reply by Ben FIT: <https://forums.garmin.com/developer/fit-sdk/f/discussion/441419/can-a-commercial-android-app-bundle-the-fit-java-sdk-com-garmin-fit-for-distribution>
- Garmin forum, "Redistribution of the Garmin FIT Java Library", reply by Ben FIT ("The license agreement allows for the use of the SDK in commercial software ... applies to the source code and to the binaries ... distributed through package managers such as Maven"): <https://forums.garmin.com/developer/fit-sdk/f/discussion/439800/redistribution-of-the-garmin-fit-java-library>
- Garmin FIT SDK news, "FIT SDK License Agreement Updates" (12 Oct 2022, Ben FIT): <https://forums.garmin.com/developer/fit-sdk/b/news-announcements/posts/fit-sdk-license-agreement-updates>

## What was checked

- The `com.garmin:fit:21.217.0` jar holds only compiled classes and Maven metadata. It contains no licence or notice file that a repackaged jar would have to keep.
- The licence text was read in full. The relevant clauses are §1 (grant), §2(a) (keep notices), §2(c) (no distribution except as permitted), §2(d) (no copyleft), §3 (interoperability), §9 (termination) and §11(g) (amendment).
