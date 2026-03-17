package org.noise_planet.noisemodelling.scripts.Geometric_Tools

import groovy.sql.Sql
import org.h2gis.utilities.GeometryMetaData
import org.h2gis.utilities.GeometryTableUtilities
import org.h2gis.utilities.TableLocation
import org.h2gis.utilities.dbtypes.DBUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Connection

title = 'Set_Height_By_Column_Name'
description = '&#10145;&#65039; Update the geometry by adding a height from the column in the input table that contains the heights or elevations .'

inputs = [
        tableName: [
                title      : 'Name of the table',
                name       : 'Name of the table',
                description: 'Name of the table on which the height will be modified.',
                type       : String.class
        ],
        inputSRID: [
                name       : 'Projection identifier',
                title      : 'Projection identifier',
                description: '&#127757; Original projection identifier (also called SRID) of your table. </br> </br>' +
                        'It should be an <a href="https://epsg.io/" target="_blank">EPSG</a> code, an integer with 4 or 5 digits (ex: <a href="https://epsg.io/3857" target="_blank">3857</a> is Pseudo-Mercator projection). </br> </br>' +
                        'This entry is optional because many formats already include the projection and you can also import files without geometry attributes.</br> </br>' +
                        'If the table is geometric and if this parameter is not filled and:</br>' +
                        '- the file has a .prj file associated: the SRID is deduced from the .prj </br>' +
                        '- the file has no .prj file associated: we apply the WGS84 (<a href="https://epsg.io/4326" target="_blank">EPSG:4326</a>) code </br> </br>' +
                        '&#128736; Default value: <b>4326 </b> ',
                type       : Integer.class,
        ],
        heightColumn: [
                name       : 'heightColumn',
                title      : 'heightColumn',
                description: 'The column name in the input table that contains the heights',
                type       : String.class
        ]
]

outputs = [
        result: [
                name       : 'Result output string',
                title      : 'Result output string',
                description: 'This type of result does not allow the blocks to be linked together.',
                type       : String.class
        ]
]

def exec(Connection connection, input) {

    String resultString = ""
    Logger logger = LoggerFactory.getLogger("org.noise_planet.noisemodelling")

    logger.info('Start : Set height by column name')
    logger.info("inputs {}", input)

    String table_name = input['tableName'] as String
    table_name = table_name.toUpperCase()

    String height_column = input['heightColumn'] as String
    height_column = height_column.toUpperCase()
    def srid
    if (input['inputSRID']) {
        srid = input['inputSRID'] as Integer
    }else{
        srid = GeometryTableUtilities.getSRID(connection, TableLocation.parse(table_name))
    }

    if (srid == 3785 || srid == 4326) throw new IllegalArgumentException("Error : This SRID is not metric. Please use another SRID for your table.")
    if (srid == 0) throw new IllegalArgumentException("Error : The table does not have an associated SRID.")

    GeometryMetaData metaData = GeometryTableUtilities.getMetaData(connection, TableLocation.parse(table_name, DBUtils.getDBType(connection)), "THE_GEOM");
    metaData.setSRID(srid)
    metaData.setHasZ(true)
    metaData.initGeometryType()


    String sqlUpdate = String.format(Locale.ROOT,
            "ALTER TABLE %s ALTER COLUMN %s %s USING ST_SetSRID(ST_UPDATEZ(THE_GEOM, %s), %d)",
            TableLocation.parse(table_name, DBUtils.getDBType(connection)),
            "THE_GEOM",
            metaData.getSQL(),
            height_column,
            srid
    )

    connection.createStatement().execute(sqlUpdate)

    resultString = "Process done. The " + table_name + " table   has now new heights set from column " + height_column + "."

    logger.info('End : Set height by column name')

    return resultString
}