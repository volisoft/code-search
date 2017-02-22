new Vue({
    el: '#app',
    data() {
        return {
            results: [],
            loading: false,
            error: false,
            query: ''
        }
    },
    methods: {
        search: function() {
            // Clear the error message.
            this.error = '';
            this.results = [];
            // Set the loading property to true, this will display the "Searching..." button.
            this.loading = true;

            // Making a get request to our API and passing the query to it.
            this.$http.get('/api/search?q=' + this.query).then((response) => {
                response.body.error ? this.error = response.body.error : this.results = response.data;
            this.loading = false;
            // Clear the query.
            this.query = '';
        })
        }
    }
});