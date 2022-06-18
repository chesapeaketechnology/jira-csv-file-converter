# Usage

1. Export a CSV file from Jira with the desired issues.

2. Create a copy of src/main/resources/application.conf and fill in all the settings with appropriate values.
   (Note: of course you could modify it in place as well, but take care not to commit the changes, as it will contain
   your plain text username and password.) If you place the copy in the root of the repo, it will be ignored by git.

3. Execute the `createUserMap` task to search the CSV, identify all the usernames, and create a mapping file of each
   username on the source Jira instance to the corresponding username on the target Jira instance:
   `gradlew createUserMap -Dconfig.file=path/to/config-file`

   For example, if the config file was copied to the root of the repo:
   `gradlew createUserMap -Dconfig.file=application.conf`

   The username mapping will be written to the file specified by the `us.ctic.jira.userMapFileName` property in the
   config file. For any users that cannot be found on the target Jira instance, the default username will be used
   instead.

4. Verify the user mapping in the file is correct and manually adjust the file as needed. For example, a user that does
   exist on the target instance may not be found if they have a different email and their name was misspelled or entered
   differently (e.g. Steve vs Steven).

5. Execute the 'createIssueTypeMap' task to search the source JIRA and create a mapping file to the target JIRA for 
   the projectKey list in the application configuration file.  
   Use `gradlew createIssueTypeMap -Dconfig.file=path/to/config-file` to generate an issue type mapping file.

TODO KMB: Need info on updating the ordering of the issue types (or do it in the script). Epic should be first, sub task should be last

7. Execute the `updateCsvFile` task to update the CSV export based on the mapping files:
   `gradlew updateCsvFile -Dconfig.file=path/to/config-file`

   For example, if the config file was copied to the root of the repo:
   `gradlew updateCsvFile -Dconfig.file=application.conf`

   
>Note: If an issue type mapping file exists the target CSV file will then be split into files with
given amount of issues set by `splitFileMaxIssueCount` in the configuration file. The split files will also be
prioritized in the order that issue type names are present in the file set by `issueTypeMapFileName` in the 
configuration file.

7. Provide the updated CSV file(s) to a Jira administrator to import into Jira.

>Note: it is possible to perform both of these operations at the same time using the `run` task, but it is **not**
recommended due to the potential for users to be mapped incorrectly.