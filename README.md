# Usage

1. Create a copy of src/main/resources/application.conf and fill in all the settings with appropriate values.
   (Note: of course you could modify it in place as well, but take care not to commit the changes, as it will contain
   your plain text username and password.) If you place the copy in the root of the repo, it will be ignored by git.
2. Execute the program:
   `gradlew run -Dconfig.file=path/to/config-file`
   --map-users | -m --update-file | -u