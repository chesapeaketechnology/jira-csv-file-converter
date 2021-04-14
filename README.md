# Usage

1. Create a copy of src/main/resources/application.conf and fill in all the settings with appropriate values.
   (Note: of course you could modify it in place as well, but take care not to commit the changes, as it will contain
   your plain text username and password.)
2. Execute the program:
   `gradlew run -Dconfig.file=path/to/config-file`