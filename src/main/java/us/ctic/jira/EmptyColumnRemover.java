package us.ctic.jira;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.List;

/**
 * Removes empty columns from the CSV records to reduce the size of the files to be imported.
 *
 * @since 1.1
 */
public class EmptyColumnRemover
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String FILE_SUFFIX = "_noEmptyColumns.csv";
    private final String sourceCsvFileName;

    /**
     * Constructor.
     *
     * @param sourceCsvFileName The name of the file from which to remove empty columns
     */
    public EmptyColumnRemover(String sourceCsvFileName)
    {
        this.sourceCsvFileName = sourceCsvFileName;
    }

    /**
     * Searches the file to determine which columns are empty across all records, removes them, and writes the data to a
     * new file.
     *
     * @return The name of the new file containing the data without the empty columns
     */
    public String removeEmptyColumns()
    {
        boolean[] columnHasDataArray;

        // First loop over all CSV records and keep track of any columns that don't have any data
        try (Reader reader = new FileReader(sourceCsvFileName);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT))
        {
            List<CSVRecord> records = csvParser.getRecords();

            if (records.isEmpty())
            {
                logger.warn("No records found in file {}", sourceCsvFileName);
                return sourceCsvFileName;
            }

            columnHasDataArray = new boolean[records.get(0).size()];
            for (int i = 1; i < records.size(); i++) // Skip header row (since it will obviously not be empty)
            {
                CSVRecord record = records.get(i);
                for (int columnIndex = 0; columnIndex < columnHasDataArray.length; columnIndex++)
                {
                    final boolean columnHasData = columnHasDataArray[columnIndex];

                    // We only need to check the record for columns where we haven't found data yet
                    if (!columnHasData)
                    {
                        final String columnText = record.get(columnIndex);
                        columnHasDataArray[columnIndex] = columnText != null && !columnText.isEmpty();
                    }
                }
            }
        } catch (IOException e)
        {
            logger.error("Error parsing file: {}; empty columns not removed", sourceCsvFileName, e);
            return sourceCsvFileName;
        }

        final String fileNameWithoutExtension = ParseUtils.getFileNameWithoutExtension(sourceCsvFileName);
        final String targetFileName = fileNameWithoutExtension + FILE_SUFFIX;

        // Loop back over the records again, but only write out columns with data
        try (Reader reader = new FileReader(sourceCsvFileName);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT);
             CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(targetFileName), CSVFormat.DEFAULT))
        {
            List<CSVRecord> records = csvParser.getRecords();

            for (CSVRecord record : records)
            {
                for (int columnIndex = 0; columnIndex < columnHasDataArray.length; columnIndex++)
                {
                    final boolean columnHasData = columnHasDataArray[columnIndex];

                    if (columnHasData)
                    {
                        csvPrinter.print(record.get(columnIndex));
                    }
                }
                csvPrinter.println(); // Print the record separator
            }
        } catch (IOException e)
        {
            logger.error("Error writing to file: {}", targetFileName, e);
        }

        return targetFileName;
    }
}
